package org.sakaiproject.delegatedaccess.model;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

import org.sakaiproject.hierarchy.model.HierarchyNode;

/**
 * This is essentially a wrapper for HierarchyNode since Wicket expects it to be serialized but 
 * it's not.
 * 
 * @author Bryan Holladay (holladay@longsight.com)
 *
 */
public class HierarchyNodeSerialized implements Serializable {

	public String title = "";
	public String description = "";
	public Set<String> directChildNodeIds = new HashSet<String>();
	public Set<String> directParentNodeIds = new HashSet<String>();
	public Set<String> childNodeIds = new HashSet<String>();
	public String id = "";
	public String permKey = "";

	public HierarchyNodeSerialized(HierarchyNode hierarchyNode){
		if(hierarchyNode != null){
			this.title = hierarchyNode.title;
			this.description = hierarchyNode.description;
			this.id = hierarchyNode.id;
			this.directChildNodeIds = hierarchyNode.directChildNodeIds;
			this.childNodeIds = hierarchyNode.childNodeIds;
			this.permKey = hierarchyNode.permToken;
			this.directParentNodeIds = hierarchyNode.directParentNodeIds;
		}
	}
}
