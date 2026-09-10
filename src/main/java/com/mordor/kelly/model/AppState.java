package com.mordor.kelly.model;

import com.mordor.kelly.service.ImClient;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.image.Image;

public final class AppState {

    private final String username;
    private final ImClient client;
    private final boolean offline;
    private final StringProperty peerDisplay = new SimpleStringProperty("等待对方");
    private final BooleanProperty online = new SimpleBooleanProperty(true);
    private final ObjectProperty<Image> avatar = new SimpleObjectProperty<>();

    public AppState(String username, ImClient client) {
        this(username, client, null);
    }

    public AppState(String username, ImClient client, Image avatar) {
        this(username, client, avatar, false);
    }

    public AppState(String username, ImClient client, Image avatar, boolean offline) {
        this.username = username;
        this.client = client;
        this.avatar.set(avatar);
        this.offline = offline;
        if (offline) {
            this.online.set(false);
            this.peerDisplay.set("脱机");
        }
    }

    public static AppState offline(String username, Image avatar) {
        return new AppState(username, null, avatar, true);
    }

    public String username() {
        return username;
    }

    public String peerName() {
        return peerDisplay.get();
    }

    public ImClient client() {
        return client;
    }

    public Image avatar() {
        return avatar.get();
    }

    public ObjectProperty<Image> avatarProperty() {
        return avatar;
    }

    public void setAvatar(Image value) {
        avatar.set(value);
    }

    public StringProperty peerDisplayProperty() {
        return peerDisplay;
    }

    public BooleanProperty onlineProperty() {
        return online;
    }

    public void setPeerDisplay(String value) {
        peerDisplay.set(value);
    }

    public void setOnline(boolean value) {
        online.set(value);
    }

    public boolean offline() {
        return offline;
    }
}
