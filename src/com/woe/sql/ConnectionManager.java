package com.woe.sql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class ConnectionManager {

    private static String USERNAME = "admin";
    private static String PASSWORD = "admin";
    private static String HOST = "localhost";
    private static String PORT = "5505";
    private static String DB = "C2F4E3";


    private static void loadDriver() {
	try {
		Class.forName("org.postgresql.Driver");
	} catch (Exception ex) {
	    ex.printStackTrace();
	}
    }

    public static Connection getConnection() {
	loadDriver();
	Connection c = null;
	try {
		String url = "jdbc:postgresql://"+HOST+":"+PORT+"/" + DB;
		c = DriverManager.getConnection(url, USERNAME, PASSWORD);	  
	} catch (SQLException e) {
	    e.printStackTrace();
	}
	return c;
    }
}
