package io.fathereye.panel.view;

import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TableCell;
import javafx.collections.FXCollections;

/**
 * Custom TableCell for editing player ranks in the Arcanum Players section.
 * Provides a ChoiceBox populated with available rank IDs for easy rank changes.
 */
class ArcanumPlayerRankCell extends TableCell<ArcanumPane.PlayerRow, ChoiceBox<String>> {
    
    private final ArcanumPane parent;
    private final ChoiceBox<String> choiceBox = new ChoiceBox<>();
    
    public ArcanumPlayerRankCell(ArcanumPane parent) {
        this.parent = parent;
        setupChoiceBox();
    }
    
    private void setupChoiceBox() {
        choiceBox.setPrefWidth(120);
        choiceBox.setOnAction(e -> {
            ArcanumPane.PlayerRow row = getTableView().getItems().get(getIndex());
            String newRank = choiceBox.getValue();
            if (newRank != null && !newRank.equals(row.rank)) {
                parent.changePlayerRank(row.uuid, newRank);
            }
        });
    }
    
    @Override
    protected void updateItem(ChoiceBox<String> item, boolean empty) {
        super.updateItem(item, empty);
        
        if (empty || getIndex() >= getTableView().getItems().size()) {
            setGraphic(null);
        } else {
            ArcanumPane.PlayerRow row = getTableView().getItems().get(getIndex());
            choiceBox.setItems(FXCollections.observableArrayList(parent.getRankIds()));
            choiceBox.setValue(row.rank);
            setGraphic(choiceBox);
        }
    }
}