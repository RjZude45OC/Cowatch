package com.cowatch.client.ui;

import com.cowatch.client.update.Updater;
import com.cowatch.client.util.Async;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import java.net.URL;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Checks for a newer release at launch and, if the user agrees, downloads it,
 * hands it to the installer script and quits so the script can swap it in.
 */
final class UpdatePrompt {

    private static final Logger log = Logger.getLogger(UpdatePrompt.class.getName());

    private final Stage owner;

    private UpdatePrompt(Stage owner) {
        this.owner = owner;
    }

    /** Runs the check in the background; does nothing visible if up to date. */
    static void checkInBackground(Stage owner) {
        UpdatePrompt prompt = new UpdatePrompt(owner);
        Async.run("update-check", () ->
                Updater.check().ifPresent(u -> Platform.runLater(() -> prompt.offer(u))));
    }

    private void offer(Updater.Update update) {
        ButtonType now = new ButtonType("Update now", ButtonBar.ButtonData.OK_DONE);
        ButtonType later = new ButtonType("Later", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = styled(new Alert(Alert.AlertType.INFORMATION, "", now, later));
        alert.setTitle("cowatch — Update available");
        alert.setHeaderText("A new version of cowatch is ready");
        alert.setContentText("Version " + update.version + " is available (you have "
                + Updater.currentVersion() + "). cowatch will close, update and open again.");
        alert.showAndWait().filter(now::equals).ifPresent(b -> download(update));
    }

    private void download(Updater.Update update) {
        ProgressBar bar = new ProgressBar(0);
        bar.setMaxWidth(Double.MAX_VALUE);
        Label status = new Label("Downloading…");
        VBox box = new VBox(10, status, bar);
        box.setPadding(new Insets(4, 0, 4, 0));

        Alert progress = styled(new Alert(Alert.AlertType.NONE, "", ButtonType.CANCEL));
        progress.setTitle("cowatch — Updating");
        progress.setHeaderText("Downloading version " + update.version);
        progress.getDialogPane().setContent(box);

        Thread worker = new Thread(() -> {
            try {
                Path installer = Updater.download(update, f -> Platform.runLater(() -> bar.setProgress(f)));
                Updater.installAfterExit(installer);
                Platform.runLater(() -> {
                    progress.setResult(ButtonType.OK);
                    progress.close();
                    quit();
                });
            } catch (InterruptedException e) {
                log.info("Update download cancelled");
            } catch (Exception e) {
                log.log(Level.WARNING, "Update failed", e);
                Platform.runLater(() -> {
                    progress.setResult(ButtonType.CANCEL);
                    progress.close();
                    showFailure(e.getMessage());
                });
            }
        }, "cowatch-update-download");
        worker.setDaemon(true);
        worker.start();

        progress.showAndWait()
                .filter(ButtonType.CANCEL::equals)
                .ifPresent(b -> worker.interrupt());
    }

    private void showFailure(String message) {
        Alert alert = styled(new Alert(Alert.AlertType.ERROR));
        alert.setTitle("cowatch — Update failed");
        alert.setHeaderText("The update didn't finish");
        alert.setContentText((message == null ? "Something went wrong." : message)
                + "\n\ncowatch will check again next time it starts.");
        alert.showAndWait();
    }

    /**
     * Runs the window's normal close path, so mpv and the server socket are
     * released, then exits so the installer's wait for this process ends.
     */
    private void quit() {
        if (owner.getOnCloseRequest() != null) {
            owner.getOnCloseRequest().handle(new WindowEvent(owner, WindowEvent.WINDOW_CLOSE_REQUEST));
        }
        owner.hide();
        Platform.exit();
        // A stray non-daemon thread must not hold the installer up.
        Thread killer = new Thread(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException ignored) { }
            System.exit(0);
        });
        killer.setDaemon(true);
        killer.start();
    }

    /** Same treatment as MainWindow's error dialogs: dark theme, sane width. */
    private <T extends Alert> T styled(T alert) {
        alert.initOwner(owner);
        URL css = CoWatchApp.class.getResource(CoWatchApp.CSS_PATH);
        if (css != null) alert.getDialogPane().getStylesheets().add(css.toExternalForm());
        alert.getDialogPane().setPrefWidth(460);
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        return alert;
    }
}
