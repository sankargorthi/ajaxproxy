package com.thedeanda.ajaxproxy.ui;

import javax.swing.table.DefaultTableColumnModel;
import javax.swing.table.TableColumn;

public class ProxyColumnModel extends DefaultTableColumnModel {
	private static final long serialVersionUID = 1L;

	public ProxyColumnModel() {
		TableColumn col = new TableColumn(0, 300);
		col.setHeaderValue("Domain");
		this.addColumn(col);

		col = new TableColumn(1, 100);
		col.setHeaderValue("Port");
		this.addColumn(col);

		col = new TableColumn(2, 400);
		col.setHeaderValue("Path");
		this.addColumn(col);

		col = new TableColumn(3, 150);
		col.setHeaderValue("Prefix");
		this.addColumn(col);
	}
}
