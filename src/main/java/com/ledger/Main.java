package com.ledger;

import com.ledger.api.ApiServer;
import com.ledger.db.Database;
import com.ledger.service.LedgerService;

public class Main {
    public static void main(String[] args) throws Exception {
        String portSetting = args.length > 0 ? args[0] : System.getenv().getOrDefault("PORT", "8080");
        String dbPath = System.getenv().getOrDefault("LEDGER_DB_PATH", "./data/ledger");

        ApiServer server = new ApiServer(new LedgerService(Database.file(dbPath)), Integer.parseInt(portSetting));
        server.start();
        System.out.println("Ledger API listening on :" + server.port() + " (db: " + dbPath + ")");
    }
}
