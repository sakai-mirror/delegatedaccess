package org.sakaiproject.delegatedaccess.tool.pages;

import java.util.ArrayList;
import java.util.List;

import javax.swing.tree.DefaultMutableTreeNode;

import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.markup.html.AjaxLink;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.list.ListItem;
import org.apache.wicket.markup.html.list.ListView;
import org.apache.wicket.markup.html.tree.AbstractTree;
import org.apache.wicket.model.AbstractReadOnlyModel;
import org.apache.wicket.model.StringResourceModel;
import org.sakaiproject.delegatedaccess.model.ListOptionSerialized;
import org.sakaiproject.delegatedaccess.model.NodeModel;

/**
 * BaseTreePage is a base page for all pages that want to use AbstractTree.  It extends BasePage as well.
 * 
 * @author Bryan Holladay (holladay@longsight.com)
 */

public abstract class BaseTreePage extends BasePage
{

	/**
	 * Returns the tree on this pages. This is used to collapse, expand, ect
	 * 
	 * @return Tree instance on this page
	 */
	protected abstract AbstractTree getTree();


	/**
	 * This saves the state of the tree.  It goes through the entire structure and saves the access and role information
	 * for each node.  It will remove and add all information.
	 * 
	 * @param userId
	 * @param defaultRole  pass this in if there is a default Role, if null, it will be ignored
	 */
	protected void updateNodeAccess(String userId, String[] defaultRole){
		if(getTree() != null){
			DefaultMutableTreeNode rootNode = (DefaultMutableTreeNode) getTree().getModelObject().getRoot();
			if(rootNode != null){
				updateNodeAccessHelper(rootNode, userId, defaultRole);
			}
			projectLogic.syncMyworkspaceToolForUser(userId);
		}
	}
	/**
	 * This is a helper function for updateNodeAccess
	 * 
	 * @param node
	 * @param userId
	 * @param defaultRole
	 */
	private void updateNodeAccessHelper(DefaultMutableTreeNode node, String userId, String[] defaultRole){
		if(node.getUserObject() != null){
			NodeModel nodeModel = (NodeModel) node.getUserObject();
			if(defaultRole != null && defaultRole.length == 2){
				nodeModel.setRealm(defaultRole[0]);
				nodeModel.setRole(defaultRole[1]);
			}
			if(nodeModel.isModified()){
				projectLogic.updateNodePermissionsForUser(nodeModel, userId);
				//now reset the node's "original" values to ensure the next save will check against
				//the newly saved settings
				nodeModel.setOriginals();
			}

			//check the rest of the children:
			for(int i = 0; i < node.getChildCount(); i++){
				updateNodeAccessHelper((DefaultMutableTreeNode)node.getChildAt(i), userId, defaultRole);
			}
		}
	}

	/**
	 * This collapses all empty folders in the tree.  This helps the user know that they need to click on the folder
	 * in order to populate the children nodes.  (used when the structure is being populated on the fly w/ajax and the
	 * folders haven't been populated yet)
	 */
	protected void collapseEmptyFolders(){
		collapseEmptyFoldersHelper((DefaultMutableTreeNode) getTree().getModelObject().getRoot());
	}

	/**
	 * Helper function for collapseEmptyFoldersHelper
	 * @param node
	 */
	protected void collapseEmptyFoldersHelper(DefaultMutableTreeNode node){
		if(node != null){
			if(!node.isLeaf() && node.getChildCount() == 0){
				//this is a node that isn't a leaf but hasn't had the children updated, make it collapse
				getTree().getTreeState().collapseNode(node);
			}
			for(int i = 0; i < node.getChildCount(); i++){
				collapseEmptyFoldersHelper((DefaultMutableTreeNode)node.getChildAt(i));
			}
		}
	}

	protected AjaxLink getExpandCollapseLink(){
		//Expand Collapse Link:
		final Label expandCollapse = new Label("expandCollapse", new StringResourceModel("exapndNodes", null));
		expandCollapse.setOutputMarkupId(true);
		AjaxLink expandLink  = new AjaxLink("expandAll")
		{
			boolean expand = true;
			@Override
			public void onClick(AjaxRequestTarget target)
			{
				if(expand){
					getTree().getTreeState().expandAll();
					expandCollapse.setDefaultModel(new StringResourceModel("collapseNodes", null));
					collapseEmptyFolders();
				}else{
					getTree().getTreeState().collapseAll();
					expandCollapse.setDefaultModel(new StringResourceModel("exapndNodes", null));
				}
				target.addComponent(expandCollapse);
				getTree().updateTree(target);
				expand = !expand;

			}
			@Override
			public boolean isVisible() {
				return getTree().getDefaultModelObject() != null;
			}
		};
		expandLink.add(expandCollapse);
		return expandLink;
	}
	
	private void createInheritedSpan(){
		WebMarkupContainer inheritedSpan = new WebMarkupContainer("inheritedSpan");
		inheritedSpan.setOutputMarkupId(true);
		final String inheritedSpanId = inheritedSpan.getMarkupId();
		add(inheritedSpan);
		
		AbstractReadOnlyModel<List<? extends ListOptionSerialized>> inheritedRestrictedToolsModel = new AbstractReadOnlyModel<List<? extends ListOptionSerialized>>(){
			private static final long serialVersionUID = 1L;

			@Override
			public List<? extends ListOptionSerialized> getObject() {
				return new ArrayList<ListOptionSerialized>();
			}

		};
		
		final ListView<ListOptionSerialized> inheritedListView = new ListView<ListOptionSerialized>("inheritedRestrictedTools",inheritedRestrictedToolsModel){
			private static final long serialVersionUID = 1L;
			@Override
			protected void populateItem(ListItem<ListOptionSerialized> item) {
				ListOptionSerialized tool = (ListOptionSerialized) item.getModelObject();
				Label name = new Label("name", tool.getName());
				item.add(name);
			}
		};
		inheritedListView.setOutputMarkupId(true);
		inheritedSpan.add(inheritedListView);
		
		
		AjaxLink<Void> closeInheritedSpanLink = new AjaxLink("closeInheritedSpanLink"){
			@Override
			public void onClick(AjaxRequestTarget arg0) {
			}
		};
		inheritedSpan.add(closeInheritedSpanLink);

		Label inheritedNodeTitle = new Label("inheritedNodeTitle", "");
		inheritedSpan.add(inheritedNodeTitle);
		
		
		
		Label noInheritedToolsLabel = new Label("noToolsInherited", new StringResourceModel("inheritedNothing", null));
		inheritedSpan.add(noInheritedToolsLabel);
	
	}

}