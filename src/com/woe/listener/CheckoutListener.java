package com.woe.listener;

import java.sql.*;

import com.woe.adapter.InventoryAdapter;
import com.woe.sql.ConnectionManager;

class Listener extends Thread {

	private static String PRODUCT_ADDED = "productAdded";
	private static String PRODUCT_UPDATED = "productUpdated";

	private Connection conn;
	private org.postgresql.PGConnection pgconn;

	Listener(Connection conn) throws SQLException {
		this.conn = conn;
		this.pgconn = (org.postgresql.PGConnection) conn;
		Statement stmt = conn.createStatement();
		stmt.execute("LISTEN " + PRODUCT_ADDED);
		stmt.execute("LISTEN " + PRODUCT_UPDATED);
		stmt.close();
	}

	public void run() {
		while (true) {
			try {
				Statement stmt = conn.createStatement();
				ResultSet rs = stmt.executeQuery("SELECT 1");
				rs.close();
				stmt.close();

				org.postgresql.PGNotification notifications[] = pgconn
						.getNotifications();
				if (notifications != null) {
					for (int i = 0; i < notifications.length; i++) {
						//System.out.println("Got notification: "+ notifications[i].getName());
						InventoryAdapter iv = new InventoryAdapter();
						iv.start();
						break;
					}
				}
				Thread.sleep(5000);
			} catch (SQLException sqle) {
				sqle.printStackTrace();
			} catch (InterruptedException ie) {
				ie.printStackTrace();
			}
		}
	}
}

public class CheckoutListener {

	public static void main(String args[]) throws Exception {
		Connection lConn = ConnectionManager.getConnection();
		Listener listener = new Listener(lConn);
		listener.start();
	}
}
