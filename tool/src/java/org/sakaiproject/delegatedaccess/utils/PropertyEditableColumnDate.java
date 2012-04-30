package org.sakaiproject.delegatedaccess.utils;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeNode;

import org.apache.wicket.Component;
import org.apache.wicket.MarkupContainer;
import org.apache.wicket.extensions.markup.html.tree.table.ColumnLocation;
import org.apache.wicket.extensions.markup.html.tree.table.IColumn;
import org.apache.wicket.extensions.markup.html.tree.table.IRenderable;
import org.apache.wicket.extensions.markup.html.tree.table.PropertyRenderableColumn;
import org.apache.wicket.model.PropertyModel;
import org.sakaiproject.delegatedaccess.model.NodeModel;
import org.sakaiproject.delegatedaccess.tool.pages.EditablePanelDate;
import org.sakaiproject.delegatedaccess.tool.pages.EditablePanelDateText;
import org.sakaiproject.delegatedaccess.tool.pages.EditablePanelEmpty;

public class PropertyEditableColumnDate extends PropertyRenderableColumn
{
	private static final long serialVersionUID = 1L;
	private boolean startDate = false;

	public PropertyEditableColumnDate(ColumnLocation location, String header, String propertyExpression, boolean startDate)
	{
		super(location, header, propertyExpression);
		this.startDate = startDate;
	}

	/**
	 * @see IColumn#newCell(MarkupContainer, String, TreeNode, int)
	 */
	public Component newCell(MarkupContainer parent, String id, TreeNode node, int level)
	{
		if(!((NodeModel) ((DefaultMutableTreeNode) node).getUserObject()).isNodeEditable()){
			return new EditablePanelEmpty(id);
		}
		
		if(((NodeModel) ((DefaultMutableTreeNode) node).getUserObject()).isDirectAccess()){
			return new EditablePanelDate(id, new PropertyModel(node, getPropertyExpression()), (NodeModel) ((DefaultMutableTreeNode) node).getUserObject(), node, startDate);
		}else{
			return new EditablePanelDateText(id, new PropertyModel(node, getPropertyExpression()), (NodeModel) ((DefaultMutableTreeNode) node).getUserObject(), node, startDate);
		}
	}

	/**
	 * @see IColumn#newCell(TreeNode, int)
	 */
	public IRenderable newCell(TreeNode node, int level)
	{
		return null;
	}


}
