package com.pierchala.desktopclient;

import com.pierchala.desktopclient.db.DatabaseManager;

public class Launcher {
    public static void main(String[] args) {
        if (args.length > 0) {
            DatabaseManager.setDatabaseFile(args[0]);
        }
        ClientApp.main(args);
    }
}