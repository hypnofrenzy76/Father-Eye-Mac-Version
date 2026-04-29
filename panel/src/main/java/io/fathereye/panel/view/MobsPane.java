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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class MobsPane {

    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final TableView<Row> table = new TableView<>(rows);
    private final Label totalLabel = new Label("--");
    private final VBox root = new VBox(6);

    public MobsPane() {
        addCol("Dimension", r -> r.dim);
        addCol("Mod", r -> r.mod);
        addCol("Total", r -> r.total);
        addCol("Hostile", r -> r.hostile);
        addCol("Passive", r -> r.passive);
        addCol("Items", r -> r.items);

        totalLabel.setStyle("-fx-padding: 4;");
        root.setPadding(new Insets(8));
        root.getChildren().addAll(totalLabel, table);
        VBox.setVgrow(table, Priority.ALWAYS);
    }

    public VBox root() { return root; }

    public void onSnapshot(JsonNode payload) {
        if (payload == null) return;
        JsonNode data = payload.get("data");
        if (data == null) return;
        JsonNode byDim = data.get("byDim");
        if (byDim == null) return;

        List<Row> newRows = new ArrayList<>();
        int grandTotal = 0;
        Iterator<Map.Entry<String, JsonNode>> dims = byDim.fields();
        while (dims.hasNext()) {
            Map.Entry<String, JsonNode> dimEntry = dims.next();
            String dim = dimEntry.getKey();
            JsonNode mods = dimEntry.getValue();
            Iterator<Map.Entry<String, JsonNode>> modIt = mods.fields();
            while (modIt.hasNext()) {
                Map.Entry<String, JsonNode> me = modIt.next();
                Row r = new Row();
                r.dim = dim;
                r.mod = me.getKey();
                JsonNode counts = me.getValue();
                int total = counts.path("total").asInt(0);
                r.total = String.valueOf(total);
                r.hostile = String.valueOf(counts.path("hostile").asInt(0));
                r.passive = String.valueOf(counts.path("passive").asInt(0));
                r.items = String.valueOf(counts.path("items").asInt(0));
                grandTotal += total;
                newRows.add(r);
            }
        }
        final int finalTotal = grandTotal;

        Platform.runLater(() -> {
            rows.setAll(newRows);
            totalLabel.setText("Total entities: " + finalTotal);
        });
    }

    private void addCol(String name, java.util.function.Function<Row, String> getter) {
        TableColumn<Row, String> c = new TableColumn<>(name);
        c.setCellValueFactory(cd -> new SimpleStringProperty(getter.apply(cd.getValue())));
        table.getColumns().add(c);
    }

    public static final class Row {
        public String dim, mod, total, hostile, passive, items;
    }
}
