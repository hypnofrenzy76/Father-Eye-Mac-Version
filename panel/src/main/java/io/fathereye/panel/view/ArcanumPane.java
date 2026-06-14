package io.fathereye.panel.view;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fathereye.panel.ipc.PipeClient;
import io.fathereye.panel.util.PlatformPaths;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.util.converter.IntegerStringConverter;
import javafx.util.converter.DoubleStringConverter;
import javafx.util.StringConverter;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pnl-71: Live admin editor for the Arcanum server-side essentials mod.
 * Pnl-72: Full implementation of two-way config editing, table editing,
 * warp-access matrix, announcements/MOTD persistence, kit items editor.
 * 
 * Provides a comprehensive interface for editing ranks, kits, warps, 
 * announcements, MOTD, and player management through bridge RPCs.
 * The pane gracefully handles config mutations by editing the fetched 
 * JsonNode tree in place to preserve unknown fields.
 */
public final class ArcanumPane {

    private static final Logger LOG = LoggerFactory.getLogger("FatherEye-ArcanumPane");

    // UI Components
    private final BorderPane root = new BorderPane();
    private final ListView<String> sectionList = new ListView<>();
    private final StackPane editorArea = new StackPane();
    private final Button reloadBtn = new Button("Reload from server");
    private final Button applyBtn = new Button("Apply to server");
    private final Label statusLabel = new Label("Ready");
    
    // Section editors
    private VBox ranksEditor;
    private VBox kitsEditor;
    private VBox warpsEditor;
    private VBox announcementsEditor;
    private VBox motdEditor;
    private VBox playersEditor;
    
    // Rank detail editor components
    private TableView<RankRow> ranksTable;
    private ListView<String> selectedRankPermissions;
    private VBox warpAccessMatrix;
    private CheckBox allWarpsCheckBox;
    
    // Kit items editor components
    private TableView<KitRow> kitsTable;
    private TableView<ItemRow> kitItemsTable;
    
    // Players editor components
    private TableView<PlayerRow> playersTable;
    
    // Globals editors
    private ChoiceBox<String> defaultRankChoice;
    private Spinner<Integer> teleportWarmupSpinner;
    
    // Announcements/MOTD components
    private CheckBox announcementsEnabledBox;
    private Spinner<Integer> announcementsIntervalSpinner;
    private TextField announcementsPrefixField;
    private ListView<String> announcementsMessagesList;
    private CheckBox motdEnabledBox;
    private TextField motdLine1Field;
    private TextField motdLine2Field;
    private Spinner<Integer> motdGlyphRotateSpinner;
    private TextField motdGlyphAlphabetField;
    
    // Data
    private volatile ObjectNode configRoot;
    private volatile int configVersion = 0;
    private volatile boolean dirty = false;
    private PipeClient pipeClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // Ranks data structures
    private final ObservableList<RankRow> ranksData = FXCollections.observableArrayList();
    private final ObservableList<KitRow> kitsData = FXCollections.observableArrayList();
    private final ObservableList<WarpRow> warpsData = FXCollections.observableArrayList();
    private final ObservableList<PlayerRow> playersData = FXCollections.observableArrayList();

    // Pnl-73: uuid -> last known name cache, fed by the live players_topic
    // snapshot and persisted across panel restarts. The bridge only knows
    // names of currently online players (it reports "Unknown" otherwise),
    // so the panel remembers every name it has ever seen join.
    private final Map<String, String> nameCache = new ConcurrentHashMap<>();
    private final Set<String> onlineUuids = ConcurrentHashMap.newKeySet();
    private final Path nameCachePath =
            PlatformPaths.appDataDir().resolve("arcanum-player-names.json");

    public ArcanumPane() {
        loadNameCache();
        buildUI();
        setupSectionNavigation();
        showPlaceholder();
    }

    public BorderPane root() { 
        return root; 
    }

    public void bindPipeClient(PipeClient client) {
        this.pipeClient = client;
        if (client != null) {
            loadConfigFromServer();
        } else {
            showPlaceholder();
        }
    }

    private void buildUI() {
        // Left section list
        sectionList.setPrefWidth(200);
        sectionList.getItems().addAll("Ranks", "Kits", "Warps", "Announcements", "MOTD", "Players");
        sectionList.getSelectionModel().selectFirst();
        
        // Bottom button bar
        HBox buttonBar = new HBox(10);
        buttonBar.setAlignment(Pos.CENTER_LEFT);
        buttonBar.setPadding(new Insets(10));
        
        reloadBtn.setOnAction(e -> reloadFromServer());
        applyBtn.setOnAction(e -> applyToServer());
        applyBtn.setDisable(true);
        
        statusLabel.setStyle("-fx-text-fill: #888;");
        
        // Add spacer to push status label right
        VBox spacer = new VBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        
        buttonBar.getChildren().addAll(reloadBtn, applyBtn, spacer, statusLabel);
        
        // Main layout
        SplitPane mainSplit = new SplitPane();
        mainSplit.getItems().addAll(sectionList, editorArea);
        mainSplit.setDividerPositions(0.25);
        
        root.setCenter(mainSplit);
        root.setBottom(buttonBar);
        
        buildEditors();
    }

    private void setupSectionNavigation() {
        sectionList.getSelectionModel().selectedItemProperty().addListener((obs, old, section) -> {
            if (section != null) {
                switchToSection(section);
            }
        });
    }

    private void switchToSection(String section) {
        editorArea.getChildren().clear();
        
        switch (section) {
            case "Ranks":
                editorArea.getChildren().add(ranksEditor);
                break;
            case "Kits":
                editorArea.getChildren().add(kitsEditor);
                break;
            case "Warps":
                editorArea.getChildren().add(warpsEditor);
                break;
            case "Announcements":
                editorArea.getChildren().add(announcementsEditor);
                break;
            case "MOTD":
                editorArea.getChildren().add(motdEditor);
                break;
            case "Players":
                editorArea.getChildren().add(playersEditor);
                loadPlayersFromServer();
                break;
            default:
                showPlaceholder();
                break;
        }
    }

    private void buildEditors() {
        ranksEditor = buildRanksEditor();
        kitsEditor = buildKitsEditor();
        warpsEditor = buildWarpsEditor();
        announcementsEditor = buildAnnouncementsEditor();
        motdEditor = buildMotdEditor();
        playersEditor = buildPlayersEditor();
    }

    private VBox buildRanksEditor() {
        VBox editor = new VBox(10);
        editor.setPadding(new Insets(15));
        
        Label title = new Label("Ranks Configuration");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        
        // Globals editor
        HBox globalsBox = new HBox(15);
        globalsBox.setAlignment(Pos.CENTER_LEFT);
        
        Label defaultRankLabel = new Label("Default Rank:");
        defaultRankChoice = new ChoiceBox<>();
        defaultRankChoice.setPrefWidth(120);
        defaultRankChoice.setOnAction(e -> markDirty());
        
        Label warmupLabel = new Label("Teleport Warmup (s):");
        teleportWarmupSpinner = new Spinner<>(0, 60, 3);
        teleportWarmupSpinner.setPrefWidth(80);
        teleportWarmupSpinner.valueProperty().addListener((obs, old, val) -> markDirty());
        
        globalsBox.getChildren().addAll(defaultRankLabel, defaultRankChoice, warmupLabel, teleportWarmupSpinner);
        
        // Ranks table with editable cells
        ranksTable = new TableView<>(ranksData);
        ranksTable.setPrefHeight(300);
        ranksTable.setEditable(true);
        
        TableColumn<RankRow, String> idCol = new TableColumn<>("ID");
        idCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().id));
        idCol.setPrefWidth(100);
        
        TableColumn<RankRow, String> nameCol = new TableColumn<>("Display Name");
        nameCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().displayName));
        nameCol.setCellFactory(TextFieldTableCell.forTableColumn());
        nameCol.setOnEditCommit(e -> {
            e.getRowValue().displayName = e.getNewValue();
            markDirty();
        });
        nameCol.setPrefWidth(150);
        
        TableColumn<RankRow, String> prefixCol = new TableColumn<>("Prefix");
        prefixCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().prefix));
        prefixCol.setCellFactory(TextFieldTableCell.forTableColumn());
        prefixCol.setOnEditCommit(e -> {
            e.getRowValue().prefix = e.getNewValue();
            markDirty();
        });
        prefixCol.setPrefWidth(150);
        
        TableColumn<RankRow, Integer> weightCol = new TableColumn<>("Weight");
        weightCol.setCellValueFactory(data -> new javafx.beans.property.SimpleIntegerProperty(data.getValue().weight).asObject());
        weightCol.setCellFactory(TextFieldTableCell.forTableColumn(new IntegerStringConverter()));
        weightCol.setOnEditCommit(e -> {
            e.getRowValue().weight = e.getNewValue();
            markDirty();
        });
        weightCol.setPrefWidth(80);
        
        TableColumn<RankRow, Integer> homeLimitCol = new TableColumn<>("Home Limit");
        homeLimitCol.setCellValueFactory(data -> new javafx.beans.property.SimpleIntegerProperty(data.getValue().homeLimit).asObject());
        homeLimitCol.setCellFactory(TextFieldTableCell.forTableColumn(new IntegerStringConverter()));
        homeLimitCol.setOnEditCommit(e -> {
            int value = Math.max(0, e.getNewValue());
            e.getRowValue().homeLimit = value;
            markDirty();
        });
        homeLimitCol.setPrefWidth(100);
        
        TableColumn<RankRow, Integer> cooldownCol = new TableColumn<>("Teleport Cooldown (s)");
        cooldownCol.setCellValueFactory(data -> new javafx.beans.property.SimpleIntegerProperty(data.getValue().teleportCooldownSeconds).asObject());
        cooldownCol.setCellFactory(TextFieldTableCell.forTableColumn(new IntegerStringConverter()));
        cooldownCol.setOnEditCommit(e -> {
            int value = Math.max(0, e.getNewValue());
            e.getRowValue().teleportCooldownSeconds = value;
            markDirty();
        });
        cooldownCol.setPrefWidth(150);
        
        ranksTable.getColumns().addAll(idCol, nameCol, prefixCol, weightCol, homeLimitCol, cooldownCol);
        
        // Add/Remove buttons
        HBox rankButtons = new HBox(10);
        Button addRankBtn = new Button("Add Rank");
        Button removeRankBtn = new Button("Remove Rank");
        
        addRankBtn.setOnAction(e -> addNewRank());
        removeRankBtn.setOnAction(e -> removeSelectedRank(ranksTable));
        
        rankButtons.getChildren().addAll(addRankBtn, removeRankBtn);
        
        // Rank detail editor
        VBox rankDetailEditor = buildRankDetailEditor();
        
        editor.getChildren().addAll(title, globalsBox, ranksTable, rankButtons, rankDetailEditor);
        return editor;
    }
    
    private VBox buildRankDetailEditor() {
        VBox detailEditor = new VBox(10);
        detailEditor.setPadding(new Insets(10));
        detailEditor.setStyle("-fx-border-color: #ccc; -fx-border-width: 1;");
        
        Label detailTitle = new Label("Rank Permissions & Warp Access");
        detailTitle.setStyle("-fx-font-weight: bold;");
        
        // Permissions editor
        Label permissionsLabel = new Label("Permissions:");
        selectedRankPermissions = new ListView<>();
        selectedRankPermissions.setPrefHeight(120);
        
        HBox permissionButtons = new HBox(10);
        TextField newPermissionField = new TextField();
        newPermissionField.setPromptText("Enter permission node...");
        Button addPermissionBtn = new Button("Add");
        Button removePermissionBtn = new Button("Remove Selected");
        
        addPermissionBtn.setOnAction(e -> {
            String perm = newPermissionField.getText().trim();
            if (!perm.isEmpty() && !selectedRankPermissions.getItems().contains(perm)) {
                selectedRankPermissions.getItems().add(perm);
                newPermissionField.clear();
                updateSelectedRankPermissions();
                markDirty();
            }
        });
        
        removePermissionBtn.setOnAction(e -> {
            String selected = selectedRankPermissions.getSelectionModel().getSelectedItem();
            if (selected != null) {
                selectedRankPermissions.getItems().remove(selected);
                updateSelectedRankPermissions();
                markDirty();
            }
        });
        
        permissionButtons.getChildren().addAll(newPermissionField, addPermissionBtn, removePermissionBtn);
        HBox.setHgrow(newPermissionField, Priority.ALWAYS);
        
        // Warp access matrix
        Label warpAccessLabel = new Label("Warp Access:");
        
        allWarpsCheckBox = new CheckBox("All warps (arcanum.warp.use.*)");
        allWarpsCheckBox.setOnAction(e -> {
            boolean checked = allWarpsCheckBox.isSelected();
            RankRow selected = ranksTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                if (checked) {
                    if (!selected.permissions.contains("arcanum.warp.use.*")) {
                        selected.permissions.add("arcanum.warp.use.*");
                    }
                } else {
                    selected.permissions.remove("arcanum.warp.use.*");
                }
                updateSelectedRankPermissions();
                updateWarpAccessMatrix();
                markDirty();
            }
        });
        
        warpAccessMatrix = new VBox(5);
        
        detailEditor.getChildren().addAll(
            detailTitle,
            permissionsLabel, selectedRankPermissions, permissionButtons,
            warpAccessLabel, allWarpsCheckBox, warpAccessMatrix
        );
        
        // Listen for rank selection changes
        ranksTable.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            updateRankDetailEditor(selected);
        });
        
        return detailEditor;
    }

    private VBox buildKitsEditor() {
        VBox editor = new VBox(10);
        editor.setPadding(new Insets(15));
        
        Label title = new Label("Kits Configuration");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        
        // Kits table with editable cells
        kitsTable = new TableView<>(kitsData);
        kitsTable.setPrefHeight(200);
        kitsTable.setEditable(true);
        
        TableColumn<KitRow, String> idCol = new TableColumn<>("ID");
        idCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().id));
        idCol.setPrefWidth(100);
        
        TableColumn<KitRow, String> nameCol = new TableColumn<>("Display Name");
        nameCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().displayName));
        nameCol.setCellFactory(TextFieldTableCell.forTableColumn());
        nameCol.setOnEditCommit(e -> {
            e.getRowValue().displayName = e.getNewValue();
            markDirty();
        });
        nameCol.setPrefWidth(150);
        
        TableColumn<KitRow, Integer> cooldownCol = new TableColumn<>("Cooldown (s)");
        cooldownCol.setCellValueFactory(data -> new javafx.beans.property.SimpleIntegerProperty(data.getValue().cooldownSeconds).asObject());
        cooldownCol.setCellFactory(TextFieldTableCell.forTableColumn(new IntegerStringConverter()));
        cooldownCol.setOnEditCommit(e -> {
            int value = Math.max(0, e.getNewValue());
            e.getRowValue().cooldownSeconds = value;
            markDirty();
        });
        cooldownCol.setPrefWidth(100);
        
        TableColumn<KitRow, String> permissionCol = new TableColumn<>("Permission");
        permissionCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().permission));
        permissionCol.setCellFactory(TextFieldTableCell.forTableColumn());
        permissionCol.setOnEditCommit(e -> {
            e.getRowValue().permission = e.getNewValue();
            markDirty();
        });
        permissionCol.setPrefWidth(200);
        
        kitsTable.getColumns().addAll(idCol, nameCol, cooldownCol, permissionCol);
        
        // Add/Remove buttons
        HBox kitButtons = new HBox(10);
        Button addKitBtn = new Button("Add Kit");
        Button removeKitBtn = new Button("Remove Kit");
        
        addKitBtn.setOnAction(e -> addNewKit());
        removeKitBtn.setOnAction(e -> removeSelectedKit(kitsTable));
        
        kitButtons.getChildren().addAll(addKitBtn, removeKitBtn);
        
        // Kit items editor
        VBox kitItemsEditor = buildKitItemsEditor();
        
        editor.getChildren().addAll(title, kitsTable, kitButtons, kitItemsEditor);
        return editor;
    }
    
    private VBox buildKitItemsEditor() {
        VBox itemsEditor = new VBox(10);
        itemsEditor.setPadding(new Insets(10));
        itemsEditor.setStyle("-fx-border-color: #ccc; -fx-border-width: 1;");
        
        Label itemsTitle = new Label("Kit Items");
        itemsTitle.setStyle("-fx-font-weight: bold;");
        
        // Items table
        kitItemsTable = new TableView<>();
        kitItemsTable.setPrefHeight(150);
        kitItemsTable.setEditable(true);
        
        TableColumn<ItemRow, String> itemIdCol = new TableColumn<>("Item ID");
        itemIdCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().itemId));
        itemIdCol.setCellFactory(TextFieldTableCell.forTableColumn());
        itemIdCol.setOnEditCommit(e -> {
            e.getRowValue().itemId = e.getNewValue();
            updateSelectedKitItems();
            markDirty();
        });
        itemIdCol.setPrefWidth(200);
        
        TableColumn<ItemRow, Integer> countCol = new TableColumn<>("Count");
        countCol.setCellValueFactory(data -> new javafx.beans.property.SimpleIntegerProperty(data.getValue().count).asObject());
        countCol.setCellFactory(TextFieldTableCell.forTableColumn(new IntegerStringConverter()));
        countCol.setOnEditCommit(e -> {
            int value = Math.max(1, Math.min(64, e.getNewValue()));
            e.getRowValue().count = value;
            updateSelectedKitItems();
            markDirty();
        });
        countCol.setPrefWidth(100);
        
        kitItemsTable.getColumns().addAll(itemIdCol, countCol);
        
        // Items buttons
        HBox itemButtons = new HBox(10);
        Button addItemBtn = new Button("Add Item");
        Button removeItemBtn = new Button("Remove Item");
        
        addItemBtn.setOnAction(e -> {
            ItemRow newItem = new ItemRow();
            newItem.itemId = "minecraft:stone";
            newItem.count = 1;
            kitItemsTable.getItems().add(newItem);
            updateSelectedKitItems();
            markDirty();
        });
        
        removeItemBtn.setOnAction(e -> {
            ItemRow selected = kitItemsTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                kitItemsTable.getItems().remove(selected);
                updateSelectedKitItems();
                markDirty();
            }
        });
        
        itemButtons.getChildren().addAll(addItemBtn, removeItemBtn);
        
        itemsEditor.getChildren().addAll(itemsTitle, kitItemsTable, itemButtons);
        
        // Listen for kit selection changes
        kitsTable.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            updateKitItemsEditor(selected);
        });
        
        return itemsEditor;
    }

    private VBox buildWarpsEditor() {
        VBox editor = new VBox(10);
        editor.setPadding(new Insets(15));
        
        Label title = new Label("Warps Configuration");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        
        // Warps table with editable cells
        TableView<WarpRow> table = new TableView<>(warpsData);
        table.setPrefHeight(300);
        table.setEditable(true);
        
        TableColumn<WarpRow, String> idCol = new TableColumn<>("ID");
        idCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().id));
        idCol.setPrefWidth(100);
        
        TableColumn<WarpRow, String> nameCol = new TableColumn<>("Display Name");
        nameCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().displayName));
        nameCol.setCellFactory(TextFieldTableCell.forTableColumn());
        nameCol.setOnEditCommit(e -> {
            e.getRowValue().displayName = e.getNewValue();
            markDirty();
        });
        nameCol.setPrefWidth(120);
        
        TableColumn<WarpRow, String> dimCol = new TableColumn<>("Dimension");
        dimCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().dimension));
        dimCol.setCellFactory(TextFieldTableCell.forTableColumn());
        dimCol.setOnEditCommit(e -> {
            e.getRowValue().dimension = e.getNewValue();
            markDirty();
        });
        dimCol.setPrefWidth(120);
        
        TableColumn<WarpRow, Double> xCol = new TableColumn<>("X");
        xCol.setCellValueFactory(data -> new javafx.beans.property.SimpleDoubleProperty(data.getValue().x).asObject());
        xCol.setCellFactory(TextFieldTableCell.forTableColumn(new DoubleStringConverter()));
        xCol.setOnEditCommit(e -> {
            e.getRowValue().x = e.getNewValue();
            markDirty();
        });
        xCol.setPrefWidth(80);
        
        TableColumn<WarpRow, Double> yCol = new TableColumn<>("Y");
        yCol.setCellValueFactory(data -> new javafx.beans.property.SimpleDoubleProperty(data.getValue().y).asObject());
        yCol.setCellFactory(TextFieldTableCell.forTableColumn(new DoubleStringConverter()));
        yCol.setOnEditCommit(e -> {
            e.getRowValue().y = e.getNewValue();
            markDirty();
        });
        yCol.setPrefWidth(80);
        
        TableColumn<WarpRow, Double> zCol = new TableColumn<>("Z");
        zCol.setCellValueFactory(data -> new javafx.beans.property.SimpleDoubleProperty(data.getValue().z).asObject());
        zCol.setCellFactory(TextFieldTableCell.forTableColumn(new DoubleStringConverter()));
        zCol.setOnEditCommit(e -> {
            e.getRowValue().z = e.getNewValue();
            markDirty();
        });
        zCol.setPrefWidth(80);
        
        TableColumn<WarpRow, Float> yawCol = new TableColumn<>("Yaw");
        yawCol.setCellValueFactory(data -> new javafx.beans.property.SimpleFloatProperty(data.getValue().yaw).asObject());
        yawCol.setCellFactory(TextFieldTableCell.forTableColumn(new StringConverter<Float>() {
            @Override
            public String toString(Float object) {
                return object == null ? "0.0" : object.toString();
            }
            @Override
            public Float fromString(String string) {
                try {
                    return Float.parseFloat(string);
                } catch (NumberFormatException e) {
                    return 0.0f;
                }
            }
        }));
        yawCol.setOnEditCommit(e -> {
            e.getRowValue().yaw = e.getNewValue();
            markDirty();
        });
        yawCol.setPrefWidth(80);
        
        TableColumn<WarpRow, Float> pitchCol = new TableColumn<>("Pitch");
        pitchCol.setCellValueFactory(data -> new javafx.beans.property.SimpleFloatProperty(data.getValue().pitch).asObject());
        pitchCol.setCellFactory(TextFieldTableCell.forTableColumn(new StringConverter<Float>() {
            @Override
            public String toString(Float object) {
                return object == null ? "0.0" : object.toString();
            }
            @Override
            public Float fromString(String string) {
                try {
                    return Float.parseFloat(string);
                } catch (NumberFormatException e) {
                    return 0.0f;
                }
            }
        }));
        pitchCol.setOnEditCommit(e -> {
            e.getRowValue().pitch = e.getNewValue();
            markDirty();
        });
        pitchCol.setPrefWidth(80);
        
        TableColumn<WarpRow, String> permissionCol = new TableColumn<>("Permission");
        permissionCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().permission));
        permissionCol.setCellFactory(TextFieldTableCell.forTableColumn());
        permissionCol.setOnEditCommit(e -> {
            e.getRowValue().permission = e.getNewValue();
            updateWarpAccessMatrix(); // Refresh warp access matrix
            markDirty();
        });
        permissionCol.setPrefWidth(150);
        
        table.getColumns().addAll(idCol, nameCol, dimCol, xCol, yCol, zCol, yawCol, pitchCol, permissionCol);
        
        // Add/Remove buttons
        HBox warpButtons = new HBox(10);
        Button addWarpBtn = new Button("Add Warp");
        Button removeWarpBtn = new Button("Remove Warp");
        
        addWarpBtn.setOnAction(e -> addNewWarp());
        removeWarpBtn.setOnAction(e -> removeSelectedWarp(table));
        
        warpButtons.getChildren().addAll(addWarpBtn, removeWarpBtn);
        
        editor.getChildren().addAll(title, table, warpButtons);
        return editor;
    }

    private VBox buildAnnouncementsEditor() {
        VBox editor = new VBox(10);
        editor.setPadding(new Insets(15));
        
        Label title = new Label("Announcements Configuration");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        
        // Enabled checkbox
        announcementsEnabledBox = new CheckBox("Enable automatic announcements");
        announcementsEnabledBox.setOnAction(e -> markDirty());
        
        // Interval spinner
        HBox intervalBox = new HBox(10);
        intervalBox.setAlignment(Pos.CENTER_LEFT);
        Label intervalLabel = new Label("Interval (seconds):");
        announcementsIntervalSpinner = new Spinner<>(5, 3600, 300);
        announcementsIntervalSpinner.valueProperty().addListener((obs, old, val) -> markDirty());
        intervalBox.getChildren().addAll(intervalLabel, announcementsIntervalSpinner);
        
        // Prefix field
        HBox prefixBox = new HBox(10);
        prefixBox.setAlignment(Pos.CENTER_LEFT);
        Label prefixLabel = new Label("Prefix:");
        announcementsPrefixField = new TextField();
        announcementsPrefixField.setPromptText("e.g., [Server]");
        announcementsPrefixField.textProperty().addListener((obs, old, val) -> markDirty());
        prefixBox.getChildren().addAll(prefixLabel, announcementsPrefixField);
        HBox.setHgrow(announcementsPrefixField, Priority.ALWAYS);
        
        // Messages list
        Label messagesLabel = new Label("Messages:");
        announcementsMessagesList = new ListView<>();
        announcementsMessagesList.setPrefHeight(200);
        
        // Message buttons
        HBox msgButtons = new HBox(10);
        Button addMsgBtn = new Button("Add Message");
        Button removeMsgBtn = new Button("Remove Message");
        Button moveUpBtn = new Button("Move Up");
        Button moveDownBtn = new Button("Move Down");
        
        addMsgBtn.setOnAction(e -> {
            TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle("Add Message");
            dialog.setHeaderText("Enter announcement message:");
            dialog.showAndWait().ifPresent(message -> {
                if (!message.trim().isEmpty()) {
                    announcementsMessagesList.getItems().add(message.trim());
                    markDirty();
                }
            });
        });
        
        removeMsgBtn.setOnAction(e -> {
            String selected = announcementsMessagesList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                announcementsMessagesList.getItems().remove(selected);
                markDirty();
            }
        });
        
        moveUpBtn.setOnAction(e -> {
            int index = announcementsMessagesList.getSelectionModel().getSelectedIndex();
            if (index > 0) {
                String item = announcementsMessagesList.getItems().remove(index);
                announcementsMessagesList.getItems().add(index - 1, item);
                announcementsMessagesList.getSelectionModel().select(index - 1);
                markDirty();
            }
        });
        
        moveDownBtn.setOnAction(e -> {
            int index = announcementsMessagesList.getSelectionModel().getSelectedIndex();
            if (index >= 0 && index < announcementsMessagesList.getItems().size() - 1) {
                String item = announcementsMessagesList.getItems().remove(index);
                announcementsMessagesList.getItems().add(index + 1, item);
                announcementsMessagesList.getSelectionModel().select(index + 1);
                markDirty();
            }
        });
        
        msgButtons.getChildren().addAll(addMsgBtn, removeMsgBtn, moveUpBtn, moveDownBtn);
        
        // Broadcast now section
        VBox broadcastBox = new VBox(5);
        Label broadcastLabel = new Label("Broadcast now:");
        HBox broadcastControls = new HBox(10);
        TextField broadcastField = new TextField();
        broadcastField.setPromptText("Type message to broadcast...");
        Button broadcastBtn = new Button("Broadcast");
        broadcastBtn.setOnAction(e -> {
            broadcastMessage(broadcastField.getText());
            broadcastField.clear();
        });
        broadcastControls.getChildren().addAll(broadcastField, broadcastBtn);
        HBox.setHgrow(broadcastField, Priority.ALWAYS);
        broadcastBox.getChildren().addAll(broadcastLabel, broadcastControls);
        
        editor.getChildren().addAll(title, announcementsEnabledBox, intervalBox, prefixBox, 
                                   messagesLabel, announcementsMessagesList, msgButtons, broadcastBox);
        return editor;
    }

    private VBox buildMotdEditor() {
        VBox editor = new VBox(10);
        editor.setPadding(new Insets(15));
        
        Label title = new Label("MOTD Configuration");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        
        // Enabled checkbox
        motdEnabledBox = new CheckBox("Enable dynamic MOTD");
        motdEnabledBox.setOnAction(e -> markDirty());
        
        // MOTD lines
        HBox line1Box = new HBox(10);
        line1Box.setAlignment(Pos.CENTER_LEFT);
        Label line1Label = new Label("Line 1:");
        motdLine1Field = new TextField();
        motdLine1Field.textProperty().addListener((obs, old, val) -> markDirty());
        line1Box.getChildren().addAll(line1Label, motdLine1Field);
        HBox.setHgrow(motdLine1Field, Priority.ALWAYS);
        
        HBox line2Box = new HBox(10);
        line2Box.setAlignment(Pos.CENTER_LEFT);
        Label line2Label = new Label("Line 2:");
        motdLine2Field = new TextField();
        motdLine2Field.textProperty().addListener((obs, old, val) -> markDirty());
        line2Box.getChildren().addAll(line2Label, motdLine2Field);
        HBox.setHgrow(motdLine2Field, Priority.ALWAYS);
        
        // Live preview
        Label previewLabel = new Label("Preview:");
        TextArea previewArea = new TextArea();
        previewArea.setEditable(false);
        previewArea.setPrefRowCount(3);
        
        // Update preview when fields change
        Runnable updatePreview = () -> {
            String preview = stripColorCodes(motdLine1Field.getText()) + "\n" + 
                           stripColorCodes(motdLine2Field.getText()).replace("§k", "▒▒▒");
            previewArea.setText(preview);
        };
        
        motdLine1Field.textProperty().addListener((obs, old, text) -> updatePreview.run());
        motdLine2Field.textProperty().addListener((obs, old, text) -> updatePreview.run());
        
        // Glyph settings
        HBox glyphRotateBox = new HBox(10);
        glyphRotateBox.setAlignment(Pos.CENTER_LEFT);
        Label glyphRotateLabel = new Label("Glyph rotate (seconds):");
        motdGlyphRotateSpinner = new Spinner<>(1, 60, 5);
        motdGlyphRotateSpinner.valueProperty().addListener((obs, old, val) -> markDirty());
        glyphRotateBox.getChildren().addAll(glyphRotateLabel, motdGlyphRotateSpinner);
        
        HBox glyphAlphabetBox = new HBox(10);
        glyphAlphabetBox.setAlignment(Pos.CENTER_LEFT);
        Label glyphAlphabetLabel = new Label("Glyph alphabet:");
        motdGlyphAlphabetField = new TextField();
        motdGlyphAlphabetField.setPromptText("Characters for §k obfuscation");
        motdGlyphAlphabetField.textProperty().addListener((obs, old, val) -> markDirty());
        glyphAlphabetBox.getChildren().addAll(glyphAlphabetLabel, motdGlyphAlphabetField);
        HBox.setHgrow(motdGlyphAlphabetField, Priority.ALWAYS);
        
        editor.getChildren().addAll(title, motdEnabledBox, line1Box, line2Box, 
                                   previewLabel, previewArea, glyphRotateBox, glyphAlphabetBox);
        return editor;
    }

    private VBox buildPlayersEditor() {
        VBox editor = new VBox(10);
        editor.setPadding(new Insets(15));
        
        Label title = new Label("Player Management");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        
        // Players table
        TableView<PlayerRow> table = new TableView<>(playersData);
        table.setPrefHeight(400);
        playersTable = table;
        
        TableColumn<PlayerRow, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().name));
        nameCol.setPrefWidth(150);
        
        TableColumn<PlayerRow, String> uuidCol = new TableColumn<>("UUID");
        uuidCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().uuid));
        uuidCol.setPrefWidth(250);
        
        TableColumn<PlayerRow, String> rankCol = new TableColumn<>("Rank");
        rankCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().rank));
        rankCol.setPrefWidth(100);
        
        TableColumn<PlayerRow, String> homesCol = new TableColumn<>("Homes");
        homesCol.setCellValueFactory(data -> new SimpleStringProperty(String.valueOf(data.getValue().homes)));
        homesCol.setPrefWidth(80);
        
        TableColumn<PlayerRow, String> onlineCol = new TableColumn<>("Online");
        onlineCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().online ? "Yes" : "No"));
        onlineCol.setPrefWidth(80);
        
        // Rank choice column
        TableColumn<PlayerRow, ChoiceBox<String>> rankChoiceCol = new TableColumn<>("Change Rank");
        rankChoiceCol.setCellFactory(col -> new ArcanumPlayerRankCell(this));
        rankChoiceCol.setPrefWidth(120);
        
        table.getColumns().addAll(nameCol, uuidCol, rankCol, homesCol, onlineCol, rankChoiceCol);
        
        // Refresh button
        Button refreshBtn = new Button("Refresh Players");
        refreshBtn.setOnAction(e -> loadPlayersFromServer());
        
        editor.getChildren().addAll(title, table, refreshBtn);
        return editor;
    }

    private void showPlaceholder() {
        editorArea.getChildren().clear();
        Label placeholder = new Label("Arcanum mod not detected on this server");
        placeholder.setStyle("-fx-font-size: 16px; -fx-text-fill: #888; -fx-font-style: italic;");
        editorArea.getChildren().add(placeholder);
    }

    private void markDirty() {
        if (!dirty) {
            dirty = true;
            applyBtn.setDisable(false);
            updateStatus("Config modified - use Apply to save changes");
        }
    }

    private void updateStatus(String message) {
        Platform.runLater(() -> {
            statusLabel.setText(message);
            if (message.contains("error") || message.contains("Error")) {
                statusLabel.setStyle("-fx-text-fill: #e07060;");
            } else if (message.contains("success") || message.contains("Applied")) {
                statusLabel.setStyle("-fx-text-fill: #88c088;");
            } else {
                statusLabel.setStyle("-fx-text-fill: #888;");
            }
        });
    }

    // Configuration loading and saving
    private void loadConfigFromServer() {
        if (pipeClient == null || pipeClient.isClosed()) {
            showPlaceholder();
            return;
        }
        
        updateStatus("Loading configuration from server...");
        
        Map<String, Object> args = new LinkedHashMap<>();
        try {
            pipeClient.sendRequest("arcanum_getConfig", args)
                    .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .whenComplete((response, error) -> {
                        Platform.runLater(() -> {
                            if (error != null) {
                                LOG.error("Failed to load Arcanum config", error);
                                showPlaceholder();
                                updateStatus("Error: Failed to load config - " + error.getMessage());
                                return;
                            }
                            
                            try {
                                processConfigResponse(response);
                                updateStatus("Configuration loaded successfully (v" + configVersion + ")");
                            } catch (Exception e) {
                                LOG.error("Failed to parse Arcanum config", e);
                                showPlaceholder();
                                updateStatus("Error: Failed to parse config - " + e.getMessage());
                            }
                        });
                    });
        } catch (IOException e) {
            LOG.error("Failed to send config request", e);
            showPlaceholder();
            updateStatus("Error: Failed to send request - " + e.getMessage());
        }
    }

    private void loadPlayersFromServer() {
        if (pipeClient == null || pipeClient.isClosed()) {
            return;
        }
        
        Map<String, Object> args = new LinkedHashMap<>();
        try {
            pipeClient.sendRequest("arcanum_getPlayers", args)
                    .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .whenComplete((response, error) -> {
                        Platform.runLater(() -> {
                            if (error != null) {
                                LOG.warn("Failed to load Arcanum players", error);
                                return;
                            }
                            
                            try {
                                processPlayersResponse(response);
                            } catch (Exception e) {
                                LOG.error("Failed to parse Arcanum players", e);
                            }
                        });
                    });
        } catch (IOException e) {
            LOG.warn("Failed to send players request", e);
        }
    }

    private void processConfigResponse(JsonNode response) {
        if (response == null) {
            throw new RuntimeException("Null response");
        }
        
        JsonNode result = response.get("result");
        if (result == null) {
            throw new RuntimeException("No result field in response");
        }
        
        // Parse defensively: config may be top-level or nested
        JsonNode configNode = result.get("config");
        if (configNode == null) {
            configNode = result;
            configVersion = result.path("configVersion").asInt(0);
        } else {
            configVersion = result.path("configVersion").asInt(0);
        }
        
        if (!(configNode instanceof ObjectNode)) {
            throw new RuntimeException("Config is not a JSON object");
        }
        
        configRoot = (ObjectNode) configNode;
        populateEditorsFromConfig();
        dirty = false;
        applyBtn.setDisable(true);
    }

    private void processPlayersResponse(JsonNode response) {
        JsonNode result = response.get("result");
        if (result == null) return;
        
        // Parse defensively: the bridge returns a map keyed by uuid, but
        // accept an array (bare or wrapped in "players") as well.
        JsonNode playersNode = result.get("players");
        if (playersNode == null) {
            playersNode = result;
        }
        
        List<JsonNode> entries = new ArrayList<>();
        if (playersNode.isArray()) {
            for (JsonNode p : playersNode) {
                entries.add(p);
            }
        } else if (playersNode.isObject()) {
            java.util.Iterator<Map.Entry<String, JsonNode>> it = playersNode.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode v = e.getValue();
                if (!v.isObject()) continue;
                ObjectNode copy = ((ObjectNode) v).deepCopy();
                if (copy.path("uuid").asText("").isEmpty()) {
                    copy.put("uuid", e.getKey());
                }
                entries.add(copy);
            }
        } else {
            return;
        }
        
        playersData.clear();
        for (JsonNode player : entries) {
            PlayerRow row = new PlayerRow();
            row.uuid = player.path("uuid").asText("");
            row.rank = player.path("rank").asText("");
            row.homes = player.has("homesCount")
                    ? player.path("homesCount").asInt(0)
                    : player.path("homes").asInt(0);
            row.online = player.path("online").asBoolean(false)
                    || onlineUuids.contains(row.uuid);
            String name = player.path("name").asText("");
            if (name.isEmpty() || "Unknown".equals(name)) {
                name = nameCache.getOrDefault(row.uuid, "Unknown");
            } else {
                nameCache.put(row.uuid, name);
            }
            row.name = name;
            playersData.add(row);
        }
    }

    /**
     * Pnl-73: fed from the live players_topic snapshot (the same feed the
     * Players tab uses). Tracks who is online right now and caches every
     * joined player's name so offline rows keep a readable name.
     * Called on the IPC thread.
     */
    public void onPlayersSnapshot(JsonNode payload) {
        if (payload == null) return;
        JsonNode data = payload.get("data");
        if (data == null) return;
        JsonNode players = data.get("players");
        if (players == null || !players.isArray()) return;
        
        Set<String> current = new HashSet<>();
        boolean cacheChanged = false;
        for (JsonNode p : players) {
            String uuid = p.path("uuid").asText("");
            String name = p.path("name").asText("");
            if (uuid.isEmpty()) continue;
            current.add(uuid);
            if (!name.isEmpty()) {
                String previous = nameCache.put(uuid, name);
                if (!name.equals(previous)) {
                    cacheChanged = true;
                }
            }
        }
        
        onlineUuids.retainAll(current);
        onlineUuids.addAll(current);
        if (cacheChanged) {
            CompletableFuture.runAsync(this::saveNameCache);
        }
        
        Platform.runLater(() -> {
            boolean changed = false;
            for (PlayerRow row : playersData) {
                boolean nowOnline = current.contains(row.uuid);
                String cachedName = nameCache.get(row.uuid);
                String newName = (cachedName != null && (row.name.isEmpty() || "Unknown".equals(row.name)))
                        ? cachedName : row.name;
                if (row.online != nowOnline || !newName.equals(row.name)) {
                    row.online = nowOnline;
                    row.name = newName;
                    changed = true;
                }
            }
            if (changed && playersTable != null) {
                playersTable.refresh();
            }
        });
    }

    private void loadNameCache() {
        try {
            if (!Files.exists(nameCachePath)) return;
            JsonNode node = objectMapper.readTree(
                    new String(Files.readAllBytes(nameCachePath), StandardCharsets.UTF_8));
            if (node == null || !node.isObject()) return;
            java.util.Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getValue().isTextual()) {
                    nameCache.put(e.getKey(), e.getValue().asText());
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to load Arcanum player name cache", e);
        }
    }

    private void saveNameCache() {
        try {
            Files.createDirectories(nameCachePath.getParent());
            ObjectNode node = objectMapper.createObjectNode();
            for (Map.Entry<String, String> e : nameCache.entrySet()) {
                node.put(e.getKey(), e.getValue());
            }
            Files.write(nameCachePath,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(node));
        } catch (Exception e) {
            LOG.warn("Failed to save Arcanum player name cache", e);
        }
    }

    private void populateEditorsFromConfig() {
        populateRanksFromConfig();
        populateKitsFromConfig();
        populateWarpsFromConfig();
        populateAnnouncementsFromConfig();
        populateMotdFromConfig();
        populateGlobalsFromConfig();
    }

    private void populateRanksFromConfig() {
        ranksData.clear();
        
        JsonNode ranks = configRoot.path("ranks");
        if (ranks.isArray()) {
            for (JsonNode rank : ranks) {
                RankRow row = new RankRow();
                row.id = rank.path("id").asText("");
                row.displayName = rank.path("displayName").asText("");
                row.prefix = rank.path("prefix").asText("");
                row.weight = rank.path("weight").asInt(0);
                row.homeLimit = rank.path("homeLimit").asInt(1);
                row.teleportCooldownSeconds = rank.path("teleportCooldownSeconds").asInt(30);
                
                // Load permissions
                JsonNode permissions = rank.path("permissions");
                if (permissions.isArray()) {
                    for (JsonNode perm : permissions) {
                        row.permissions.add(perm.asText());
                    }
                }
                
                ranksData.add(row);
            }
        }
        
        // Update UI components
        Platform.runLater(() -> {
            updateDefaultRankChoices();
            updateWarpAccessMatrix();
        });
    }

    private void populateKitsFromConfig() {
        kitsData.clear();
        
        JsonNode kits = configRoot.path("kits");
        if (kits.isArray()) {
            for (JsonNode kit : kits) {
                KitRow row = new KitRow();
                row.id = kit.path("id").asText("");
                row.displayName = kit.path("displayName").asText("");
                row.cooldownSeconds = kit.path("cooldownSeconds").asInt(0);
                row.permission = kit.path("permission").asText("");
                
                // Load items
                JsonNode items = kit.path("items");
                if (items.isArray()) {
                    for (JsonNode item : items) {
                        ItemRow itemRow = new ItemRow();
                        itemRow.itemId = item.path("itemId").asText("");
                        itemRow.count = item.path("count").asInt(1);
                        row.items.add(itemRow);
                    }
                }
                
                kitsData.add(row);
            }
        }
    }

    private void populateWarpsFromConfig() {
        warpsData.clear();
        
        JsonNode warps = configRoot.path("warps");
        if (warps.isArray()) {
            for (JsonNode warp : warps) {
                WarpRow row = new WarpRow();
                row.id = warp.path("id").asText("");
                row.displayName = warp.path("displayName").asText("");
                row.permission = warp.path("permission").asText("");
                
                // Read from nested location object
                JsonNode location = warp.path("location");
                if (location.isMissingNode()) {
                    // Fallback to flat structure for backwards compatibility
                    row.dimension = warp.path("dim").asText("minecraft:overworld");
                    row.x = warp.path("x").asDouble(0);
                    row.y = warp.path("y").asDouble(0);
                    row.z = warp.path("z").asDouble(0);
                    row.yaw = (float) warp.path("yaw").asDouble(0);
                    row.pitch = (float) warp.path("pitch").asDouble(0);
                } else {
                    row.dimension = location.path("dimension").asText("minecraft:overworld");
                    row.x = location.path("x").asDouble(0);
                    row.y = location.path("y").asDouble(0);
                    row.z = location.path("z").asDouble(0);
                    row.yaw = (float) location.path("yaw").asDouble(0);
                    row.pitch = (float) location.path("pitch").asDouble(0);
                }
                
                warpsData.add(row);
            }
        }
        
        Platform.runLater(() -> {
            updateWarpAccessMatrix();
        });
    }
    
    private void populateAnnouncementsFromConfig() {
        JsonNode announcements = configRoot.path("announcements");
        if (announcements != null && !announcements.isMissingNode()) {
            Platform.runLater(() -> {
                announcementsEnabledBox.setSelected(announcements.path("enabled").asBoolean(true));
                announcementsIntervalSpinner.getValueFactory().setValue(announcements.path("intervalSeconds").asInt(300));
                announcementsPrefixField.setText(announcements.path("prefix").asText(""));
                
                announcementsMessagesList.getItems().clear();
                JsonNode messages = announcements.path("messages");
                if (messages.isArray()) {
                    for (JsonNode message : messages) {
                        announcementsMessagesList.getItems().add(message.asText());
                    }
                }
            });
        }
    }
    
    private void populateMotdFromConfig() {
        JsonNode motd = configRoot.path("motd");
        if (motd != null && !motd.isMissingNode()) {
            Platform.runLater(() -> {
                motdEnabledBox.setSelected(motd.path("enabled").asBoolean(true));
                motdLine1Field.setText(motd.path("line1").asText(""));
                motdLine2Field.setText(motd.path("line2").asText(""));
                motdGlyphRotateSpinner.getValueFactory().setValue(motd.path("glyphRotateSeconds").asInt(5));
                motdGlyphAlphabetField.setText(motd.path("glyphAlphabet").asText(""));
            });
        }
    }
    
    private void populateGlobalsFromConfig() {
        Platform.runLater(() -> {
            // Set teleport warmup
            teleportWarmupSpinner.getValueFactory().setValue(configRoot.path("teleportWarmupSeconds").asInt(3));
            
            // Set default rank
            String defaultRank = configRoot.path("defaultRankId").asText("");
            if (!defaultRank.isEmpty()) {
                defaultRankChoice.setValue(defaultRank);
            }
        });
    }

    // Action handlers
    private void reloadFromServer() {
        if (dirty) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Confirm Reload");
            confirm.setHeaderText("Discard local changes?");
            confirm.setContentText("You have unsaved changes that will be lost if you reload from the server.");
            
            if (!confirm.showAndWait().orElse(null).equals(javafx.scene.control.ButtonType.OK)) {
                return;
            }
        }
        
        loadConfigFromServer();
    }
    
    private void syncEditorsIntoConfigRoot() {
        try {
            // Sync ranks
            ArrayNode ranksArray = objectMapper.createArrayNode();
            for (RankRow rank : ranksData) {
                // Try to find existing rank node to preserve unknown fields
                ObjectNode rankNode = null;
                JsonNode existingRanks = configRoot.path("ranks");
                if (existingRanks.isArray()) {
                    for (JsonNode existing : existingRanks) {
                        if (rank.id.equals(existing.path("id").asText())) {
                            rankNode = (ObjectNode) existing;
                            break;
                        }
                    }
                }
                
                if (rankNode == null) {
                    rankNode = objectMapper.createObjectNode();
                }
                
                rankNode.put("id", rank.id);
                rankNode.put("displayName", rank.displayName);
                rankNode.put("prefix", rank.prefix);
                rankNode.put("weight", rank.weight);
                rankNode.put("homeLimit", rank.homeLimit);
                rankNode.put("teleportCooldownSeconds", rank.teleportCooldownSeconds);
                
                ArrayNode permissionsArray = objectMapper.createArrayNode();
                for (String permission : rank.permissions) {
                    permissionsArray.add(permission);
                }
                rankNode.set("permissions", permissionsArray);
                
                ranksArray.add(rankNode);
            }
            configRoot.set("ranks", ranksArray);
            
            // Sync kits
            ArrayNode kitsArray = objectMapper.createArrayNode();
            for (KitRow kit : kitsData) {
                ObjectNode kitNode = null;
                JsonNode existingKits = configRoot.path("kits");
                if (existingKits.isArray()) {
                    for (JsonNode existing : existingKits) {
                        if (kit.id.equals(existing.path("id").asText())) {
                            kitNode = (ObjectNode) existing;
                            break;
                        }
                    }
                }
                
                if (kitNode == null) {
                    kitNode = objectMapper.createObjectNode();
                }
                
                kitNode.put("id", kit.id);
                kitNode.put("displayName", kit.displayName);
                kitNode.put("cooldownSeconds", kit.cooldownSeconds);
                kitNode.put("permission", kit.permission);
                
                ArrayNode itemsArray = objectMapper.createArrayNode();
                for (ItemRow item : kit.items) {
                    ObjectNode itemNode = objectMapper.createObjectNode();
                    itemNode.put("itemId", item.itemId);
                    itemNode.put("count", item.count);
                    itemsArray.add(itemNode);
                }
                kitNode.set("items", itemsArray);
                
                kitsArray.add(kitNode);
            }
            configRoot.set("kits", kitsArray);
            
            // Sync warps
            ArrayNode warpsArray = objectMapper.createArrayNode();
            for (WarpRow warp : warpsData) {
                ObjectNode warpNode = null;
                JsonNode existingWarps = configRoot.path("warps");
                if (existingWarps.isArray()) {
                    for (JsonNode existing : existingWarps) {
                        if (warp.id.equals(existing.path("id").asText())) {
                            warpNode = (ObjectNode) existing;
                            break;
                        }
                    }
                }
                
                if (warpNode == null) {
                    warpNode = objectMapper.createObjectNode();
                }
                
                warpNode.put("id", warp.id);
                warpNode.put("displayName", warp.displayName);
                warpNode.put("permission", warp.permission);
                
                // Create nested location object
                ObjectNode locationNode = objectMapper.createObjectNode();
                locationNode.put("dimension", warp.dimension);
                locationNode.put("x", warp.x);
                locationNode.put("y", warp.y);
                locationNode.put("z", warp.z);
                locationNode.put("yaw", warp.yaw);
                locationNode.put("pitch", warp.pitch);
                warpNode.set("location", locationNode);
                
                warpsArray.add(warpNode);
            }
            configRoot.set("warps", warpsArray);
            
            // Sync globals
            configRoot.put("defaultRankId", defaultRankChoice.getValue() != null ? defaultRankChoice.getValue() : "");
            configRoot.put("teleportWarmupSeconds", teleportWarmupSpinner.getValue());
            
            // Sync announcements
            ObjectNode announcementsNode = objectMapper.createObjectNode();
            announcementsNode.put("enabled", announcementsEnabledBox.isSelected());
            announcementsNode.put("intervalSeconds", announcementsIntervalSpinner.getValue());
            announcementsNode.put("prefix", announcementsPrefixField.getText());
            
            ArrayNode messagesArray = objectMapper.createArrayNode();
            for (String message : announcementsMessagesList.getItems()) {
                messagesArray.add(message);
            }
            announcementsNode.set("messages", messagesArray);
            configRoot.set("announcements", announcementsNode);
            
            // Sync MOTD
            ObjectNode motdNode = objectMapper.createObjectNode();
            motdNode.put("enabled", motdEnabledBox.isSelected());
            motdNode.put("line1", motdLine1Field.getText());
            motdNode.put("line2", motdLine2Field.getText());
            motdNode.put("glyphRotateSeconds", motdGlyphRotateSpinner.getValue());
            motdNode.put("glyphAlphabet", motdGlyphAlphabetField.getText());
            configRoot.set("motd", motdNode);
            
        } catch (Exception e) {
            LOG.error("Failed to sync editors into config", e);
        }
    }

    private void applyToServer() {
        if (pipeClient == null || pipeClient.isClosed() || configRoot == null) {
            return;
        }
        
        updateStatus("Applying configuration to server...");
        
        // Sync all editor changes back into configRoot
        syncEditorsIntoConfigRoot();
        
        // Remove spurious configVersion field before sending
        ObjectNode configToSend = configRoot.deepCopy();
        configToSend.remove("configVersion");
        
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("config", configToSend);
        
        try {
            pipeClient.sendRequest("arcanum_setConfig", args)
                .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((response, error) -> {
                    Platform.runLater(() -> {
                        if (error != null) {
                            LOG.error("Failed to apply Arcanum config", error);
                            updateStatus("Error: Failed to apply config - " + error.getMessage());
                            return;
                        }
                        
                        try {
                            JsonNode result = response.get("result");
                            if (result != null && result.path("ok").asBoolean(false)) {
                                dirty = false;
                                applyBtn.setDisable(true);
                                // Re-fetch to get the new version number
                                loadConfigFromServer();
                                updateStatus("Configuration applied successfully");
                            } else {
                                JsonNode errors = result != null ? result.get("errors") : null;
                                String errorMsg = "Unknown error";
                                if (errors != null && errors.isArray() && errors.size() > 0) {
                                    errorMsg = errors.get(0).asText(errorMsg);
                                }
                                updateStatus("Error: " + errorMsg);
                            }
                        } catch (Exception e) {
                            LOG.error("Failed to parse apply response", e);
                            updateStatus("Error: Failed to parse response - " + e.getMessage());
                        }
                    });
                });
        } catch (IOException e) {
            LOG.error("Failed to send apply request", e);
            updateStatus("Error: Failed to send request - " + e.getMessage());
        }
    }

    private void broadcastMessage(String message) {
        if (message == null || message.trim().isEmpty() || pipeClient == null || pipeClient.isClosed()) {
            return;
        }
        
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("message", message.trim());
        
        try {
            pipeClient.sendRequest("arcanum_announce", args)
                    .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .whenComplete((response, error) -> {
                        Platform.runLater(() -> {
                            if (error != null) {
                                LOG.warn("Failed to broadcast message", error);
                            }
                            // Note: We don't show status for broadcasts to avoid spam
                        });
                    });
        } catch (IOException e) {
            LOG.warn("Failed to send broadcast request", e);
        }
    }

    // Utility methods
    private String stripColorCodes(String text) {
        if (text == null) return "";
        return text.replaceAll("§.", "");
    }

    // Add/Remove action methods
    private void addNewRank() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Add New Rank");
        dialog.setHeaderText("Enter rank ID:");
        dialog.setContentText("Rank ID (a-z, 0-9, _ only):");
        
        dialog.showAndWait().ifPresent(id -> {
            id = id.trim().toLowerCase();
            if (id.isEmpty() || !id.matches("[a-z0-9_]+")) {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Invalid ID");
                alert.setHeaderText("Invalid rank ID");
                alert.setContentText("Rank ID must contain only lowercase letters, numbers, and underscores.");
                alert.showAndWait();
                return;
            }
            
            // Check uniqueness
            for (RankRow existing : ranksData) {
                if (existing.id.equals(id)) {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Duplicate ID");
                    alert.setHeaderText("Rank ID already exists");
                    alert.setContentText("A rank with ID '" + id + "' already exists.");
                    alert.showAndWait();
                    return;
                }
            }
            
            RankRow newRank = new RankRow();
            newRank.id = id;
            newRank.displayName = id.substring(0, 1).toUpperCase() + id.substring(1);
            newRank.prefix = "[" + newRank.displayName + "]";
            newRank.weight = 0;
            newRank.homeLimit = 1;
            newRank.teleportCooldownSeconds = 30;
            
            ranksData.add(newRank);
            updateDefaultRankChoices();
            markDirty();
        });
    }

    private void removeSelectedRank(TableView<RankRow> table) {
        RankRow selected = table.getSelectionModel().getSelectedItem();
        if (selected != null) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Remove Rank");
            confirm.setHeaderText("Remove rank '" + selected.id + "'?");
            confirm.setContentText("This action cannot be undone.");
            
            if (confirm.showAndWait().orElse(null) == javafx.scene.control.ButtonType.OK) {
                ranksData.remove(selected);
                updateDefaultRankChoices();
                updateWarpAccessMatrix();
                markDirty();
            }
        }
    }

    private void addNewKit() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Add New Kit");
        dialog.setHeaderText("Enter kit ID:");
        dialog.setContentText("Kit ID (a-z, 0-9, _ only):");
        
        dialog.showAndWait().ifPresent(id -> {
            id = id.trim().toLowerCase();
            if (id.isEmpty() || !id.matches("[a-z0-9_]+")) {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Invalid ID");
                alert.setHeaderText("Invalid kit ID");
                alert.setContentText("Kit ID must contain only lowercase letters, numbers, and underscores.");
                alert.showAndWait();
                return;
            }
            
            // Check uniqueness
            for (KitRow existing : kitsData) {
                if (existing.id.equals(id)) {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Duplicate ID");
                    alert.setHeaderText("Kit ID already exists");
                    alert.setContentText("A kit with ID '" + id + "' already exists.");
                    alert.showAndWait();
                    return;
                }
            }
            
            KitRow newKit = new KitRow();
            newKit.id = id;
            newKit.displayName = id.substring(0, 1).toUpperCase() + id.substring(1) + " Kit";
            newKit.cooldownSeconds = 0;
            newKit.permission = "";
            
            kitsData.add(newKit);
            markDirty();
        });
    }

    private void removeSelectedKit(TableView<KitRow> table) {
        KitRow selected = table.getSelectionModel().getSelectedItem();
        if (selected != null) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Remove Kit");
            confirm.setHeaderText("Remove kit '" + selected.id + "'?");
            confirm.setContentText("This action cannot be undone.");
            
            if (confirm.showAndWait().orElse(null) == javafx.scene.control.ButtonType.OK) {
                kitsData.remove(selected);
                markDirty();
            }
        }
    }

    private void addNewWarp() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Add New Warp");
        dialog.setHeaderText("Enter warp ID:");
        dialog.setContentText("Warp ID (a-z, 0-9, _ only):");
        
        dialog.showAndWait().ifPresent(id -> {
            id = id.trim().toLowerCase();
            if (id.isEmpty() || !id.matches("[a-z0-9_]+")) {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Invalid ID");
                alert.setHeaderText("Invalid warp ID");
                alert.setContentText("Warp ID must contain only lowercase letters, numbers, and underscores.");
                alert.showAndWait();
                return;
            }
            
            // Check uniqueness
            for (WarpRow existing : warpsData) {
                if (existing.id.equals(id)) {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Duplicate ID");
                    alert.setHeaderText("Warp ID already exists");
                    alert.setContentText("A warp with ID '" + id + "' already exists.");
                    alert.showAndWait();
                    return;
                }
            }
            
            WarpRow newWarp = new WarpRow();
            newWarp.id = id;
            newWarp.displayName = id.substring(0, 1).toUpperCase() + id.substring(1);
            newWarp.dimension = "minecraft:overworld";
            newWarp.x = 0;
            newWarp.y = 64;
            newWarp.z = 0;
            newWarp.yaw = 0;
            newWarp.pitch = 0;
            newWarp.permission = "";
            
            warpsData.add(newWarp);
            updateWarpAccessMatrix();
            markDirty();
        });
    }

    private void removeSelectedWarp(TableView<WarpRow> table) {
        WarpRow selected = table.getSelectionModel().getSelectedItem();
        if (selected != null) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Remove Warp");
            confirm.setHeaderText("Remove warp '" + selected.id + "'?");
            confirm.setContentText("This action cannot be undone.");
            
            if (confirm.showAndWait().orElse(null) == javafx.scene.control.ButtonType.OK) {
                warpsData.remove(selected);
                updateWarpAccessMatrix();
                markDirty();
            }
        }
    }

    // Method for player rank changing
    void changePlayerRank(String playerUuid, String newRank) {
        if (pipeClient == null || pipeClient.isClosed()) {
            return;
        }
        
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("uuid", playerUuid);
        args.put("rank", newRank);
        
        try {
            pipeClient.sendRequest("arcanum_setPlayerRank", args)
                    .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .whenComplete((response, error) -> {
                        if (error != null) {
                            LOG.warn("Failed to change player rank", error);
                        } else {
                            // Refresh player list to show new rank
                            Platform.runLater(this::loadPlayersFromServer);
                        }
                    });
        } catch (IOException e) {
            LOG.warn("Failed to send rank change request", e);
        }
    }

    List<String> getRankIds() {
        List<String> ids = new ArrayList<>();
        for (RankRow rank : ranksData) {
            ids.add(rank.id);
        }
        return ids;
    }
    
    // Helper methods for UI updates
    private void updateDefaultRankChoices() {
        if (defaultRankChoice != null) {
            String currentValue = defaultRankChoice.getValue();
            defaultRankChoice.getItems().clear();
            for (RankRow rank : ranksData) {
                defaultRankChoice.getItems().add(rank.id);
            }
            if (currentValue != null && defaultRankChoice.getItems().contains(currentValue)) {
                defaultRankChoice.setValue(currentValue);
            }
        }
    }
    
    private void updateRankDetailEditor(RankRow selectedRank) {
        selectedRankPermissions.getItems().clear();
        if (selectedRank != null) {
            selectedRankPermissions.getItems().addAll(selectedRank.permissions);
        }
        updateWarpAccessMatrix();
    }
    
    private void updateSelectedRankPermissions() {
        RankRow selectedRank = ranksTable.getSelectionModel().getSelectedItem();
        if (selectedRank != null) {
            selectedRank.permissions.clear();
            selectedRank.permissions.addAll(selectedRankPermissions.getItems());
        }
    }
    
    private void updateWarpAccessMatrix() {
        warpAccessMatrix.getChildren().clear();
        
        RankRow selectedRank = ranksTable.getSelectionModel().getSelectedItem();
        if (selectedRank == null) {
            return;
        }
        
        // Update all warps checkbox
        allWarpsCheckBox.setSelected(selectedRank.permissions.contains("arcanum.warp.use.*"));
        
        // Create checkboxes for each warp
        for (WarpRow warp : warpsData) {
            CheckBox warpCheckBox = new CheckBox(warp.id + " (" + warp.displayName + ")");
            String warpPermission = "arcanum.warp.use." + warp.id;
            warpCheckBox.setSelected(selectedRank.permissions.contains(warpPermission));
            
            warpCheckBox.setOnAction(e -> {
                if (warpCheckBox.isSelected()) {
                    if (!selectedRank.permissions.contains(warpPermission)) {
                        selectedRank.permissions.add(warpPermission);
                    }
                } else {
                    selectedRank.permissions.remove(warpPermission);
                }
                updateSelectedRankPermissions();
                markDirty();
            });
            
            warpAccessMatrix.getChildren().add(warpCheckBox);
        }
    }
    
    private void updateKitItemsEditor(KitRow selectedKit) {
        kitItemsTable.getItems().clear();
        if (selectedKit != null) {
            kitItemsTable.getItems().addAll(selectedKit.items);
        }
    }
    
    private void updateSelectedKitItems() {
        KitRow selectedKit = kitsTable.getSelectionModel().getSelectedItem();
        if (selectedKit != null) {
            selectedKit.items.clear();
            selectedKit.items.addAll(kitItemsTable.getItems());
        }
    }

    // Data classes
    public static class RankRow {
        public String id = "";
        public String displayName = "";
        public String prefix = "";
        public int weight = 0;
        public int homeLimit = 1;
        public int teleportCooldownSeconds = 30;
        public List<String> permissions = new ArrayList<>();
    }

    public static class KitRow {
        public String id = "";
        public String displayName = "";
        public int cooldownSeconds = 0;
        public String permission = "";
        public List<ItemRow> items = new ArrayList<>();
    }

    public static class WarpRow {
        public String id = "";
        public String displayName = "";
        public String dimension = "";
        public double x = 0;
        public double y = 0;
        public double z = 0;
        public float yaw = 0;
        public float pitch = 0;
        public String permission = "";
    }
    
    public static class ItemRow {
        public String itemId = "";
        public int count = 1;
    }

    public static class PlayerRow {
        public String uuid = "";
        public String name = "";
        public String rank = "";
        public int homes = 0;
        public boolean online = false;
    }
}