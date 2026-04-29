package io.fathereye.panel.view;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public final class PlayersPane {

    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final TableView<Row> table = new TableView<>(rows);
    private final Label countLabel = new Label("0 players online");
    private final VBox root = new VBox(6);

    public PlayersPane() {
        addCol("Name", r -> r.name);
        addCol("UUID", r -> r.uuid);
        addCol("Dim", r -> r.dimensionId);
        addCol("X",   r -> r.x);
        addCol("Y",   r -> r.y);
        addCol("Z",   r -> r.z);
        addCol("HP",  r -> r.health);
        addCol("Food", r -> r.food);
        addCol("Ping", r -> r.pingMs);
        addCol("Mode", r -> r.gameMode);

        countLabel.setStyle("-fx-padding: 4;");
        root.setPadding(new Insets(8));
        root.getChildren().addAll(countLabel, table);
        VBox.setVgrow(table, Priority.ALWAYS);
    }

    public VBox root() { return root; }

    public void onSnapshot(JsonNode payload) {
        if (payload == null) return;
        JsonNode data = payload.get("data");
        if (data == null) return;
        JsonNode players = data.get("players");
        if (players == null || !players.isArray()) return;

        java.util.List<Row> newRows = new java.util.ArrayList<>();
        for (JsonNode p : players) {
            Row r = new Row();
            r.uuid = p.path("uuid").asText("");
            r.name = p.path("name").asText("");
            r.dimensionId = p.path("dimensionId").asText("");
            r.x = String.format("%.1f", p.path("x").asDouble(0));
            r.y = String.format("%.1f", p.path("y").asDouble(0));
            r.z = String.format("%.1f", p.path("z").asDouble(0));
            r.health = String.valueOf(p.path("health").asInt(0));
            r.food = String.valueOf(p.path("food").asInt(0));
            r.pingMs = p.path("pingMs").asInt(0) + " ms";
            r.gameMode = p.path("gameMode").asText("");
            newRows.add(r);
        }

        Platform.runLater(() -> {
            rows.setAll(newRows);
            countLabel.setText(newRows.size() + " players online");
        });
    }

    private void addCol(String name, java.util.function.Function<Row, String> getter) {
        TableColumn<Row, String> c = new TableColumn<>(name);
        c.setCellValueFactory(cd -> new SimpleStringProperty(getter.apply(cd.getValue())));
        table.getColumns().add(c);
    }

    public static final class Row {
        public String uuid, name, dimensionId, x, y, z, health, food, pingMs, gameMode;
    }
}
