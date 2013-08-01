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
import org.sakaiproject.delegatedaccess.tool.pages.EditablePanelAdvancedUserOptions;
import org.sakaiproject.delegatedaccess.tool.pages.EditablePanelAdvancedUserOptionsText;
import org.sakaiproject.delegatedaccess.tool.pages.EditablePanelEmpty;

public class PropertyEditableColumnAdvancedUserOptions extends PropertyRenderableColumn{


	public PropertyEditableColumnAdvancedUserOptions(ColumnLocation location, String header, String propertyExpression) {
		super(location, header, propertyExpression);
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
			return new EditablePanelAdvancedUserOptions(id, new PropertyModel(node, getPropertyExpression()), (NodeModel) ((DefaultMutableTreeNode) node).getUserObject(), node);
		}else{
			return new EditablePanelAdvancedUserOptionsText(id, new PropertyModel(node, getPropertyExpression()), (NodeModel) ((DefaultMutableTreeNode) node).getUserObject(), node);
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
