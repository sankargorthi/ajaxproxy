package com.thedeanda.ajaxproxy.ui;

import javax.swing.table.DefaultTableColumnModel;
import javax.swing.table.TableColumn;

public class VariableColumnModel extends DefaultTableColumnModel {
	private static final long serialVersionUID = 1L;

	public VariableColumnModel() {
		TableColumn col = new TableColumn(0, 100);
		col.setHeaderValue("key");
		this.addColumn(col);

		col = new TableColumn(1, 500);
		col.setHeaderValue("value");
		this.addColumn(col);
	}
}
