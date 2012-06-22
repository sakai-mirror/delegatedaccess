package org.sakaiproject.delegatedaccess.logic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeModel;

import lombok.Getter;
import lombok.Setter;

import net.sf.ehcache.Cache;
import net.sf.ehcache.Element;

import org.apache.log4j.Logger;
import org.sakaiproject.coursemanagement.api.AcademicSession;
import org.sakaiproject.delegatedaccess.dao.DelegatedAccessDao;
import org.sakaiproject.delegatedaccess.model.HierarchyNodeSerialized;
import org.sakaiproject.delegatedaccess.model.ListOptionSerialized;
import org.sakaiproject.delegatedaccess.model.NodeModel;
import org.sakaiproject.delegatedaccess.model.SearchResult;
import org.sakaiproject.delegatedaccess.model.SiteSearchResult;
import org.sakaiproject.delegatedaccess.util.DelegatedAccessConstants;
import org.sakaiproject.delegatedaccess.util.DelegatedAccessMutableTreeNode;
import org.sakaiproject.hierarchy.HierarchyService;
import org.sakaiproject.hierarchy.model.HierarchyNode;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteService.SelectionType;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.api.Tool;
import org.sakaiproject.user.api.User;


/**
 * Implementation of {@link ProjectLogic}
 * 
 * @author Bryan Holladay (holladay@longsight.com)
 *
 */
public class ProjectLogicImpl implements ProjectLogic {

	private static final Logger log = Logger.getLogger(ProjectLogicImpl.class);
	@Getter @Setter
	private SakaiProxy sakaiProxy;
	@Getter @Setter
	private HierarchyService hierarchyService;
	@Getter @Setter
	private DelegatedAccessDao dao;
	//NodeCache stores HierarchyNodeSerialed nodes for faster lookups
	@Getter @Setter
	private Cache nodeCache;
	//Stores restricted tools map for users when they log back in
	@Getter @Setter
	private Cache restrictedToolsCache;
	/**
	 * init - perform any actions required here for when this bean starts up
	 */
	public void init() {
		log.info("init");
	}

	/**
	 * returns the node for this id
	 * @param id
	 * @return
	 */
	public HierarchyNodeSerialized getNode(String id){
		return new HierarchyNodeSerialized(hierarchyService.getNodeById(id));
	}


	/**
	 * {@inheritDoc}
	 */
	public void updateNodePermissionsForUser(NodeModel nodeModel, String userId){
		//first step, remove all permissions so you can have a clear palet
		removeAllUserPermissions(nodeModel.getNodeId(), userId);

		//save access admin setting
		saveAccessAdmin(nodeModel.isAccessAdmin(), nodeModel.getNodeId(), userId);
		
		//save shopping period admin information
		saveShoppingPeriodAdmin(nodeModel.isShoppingPeriodAdmin(), nodeModel.getNodeId(), userId);

		if(nodeModel.isDirectAccess()){
			//if direct access, add permissions, otherwise, leave it blank

			//site access permission
			hierarchyService.assignUserNodePerm(userId, nodeModel.getNodeId(), DelegatedAccessConstants.NODE_PERM_SITE_VISIT, false);

			//realm & role permissions
			saveRealmAndRoleAccess(userId, nodeModel.getRealm(), nodeModel.getRole(), nodeModel.getNodeId());

			//tool permissions:
			List<String> restrictedTools = new ArrayList<String>();
			for(ListOptionSerialized tool : nodeModel.getRestrictedTools()){
				if(tool.isSelected()){
					restrictedTools.add(tool.getId());
				}
			}
			if(!restrictedTools.isEmpty()){
				saveRestrictedToolsForUser(userId, nodeModel.getNodeId(), restrictedTools);
			}

			//term
			List<String> terms = new ArrayList<String>();
			for(ListOptionSerialized term : nodeModel.getTerms()){
				if(term.isSelected()){
					terms.add(term.getId());
				}
			}
			if(!terms.isEmpty()){
				saveTermsForUser(userId, nodeModel.getNodeId(), terms);
			}
			
			//save shopping period information
			if(DelegatedAccessConstants.SHOPPING_PERIOD_USER.equals(userId)){
				saveShoppingPeriodAuth(nodeModel.getShoppingPeriodAuth(), nodeModel.getNodeId());
				saveShoppingPeriodStartDate(nodeModel.getShoppingPeriodStartDate(), nodeModel.getNodeId());
				saveShoppingPeriodEndDate(nodeModel.getShoppingPeriodEndDate(), nodeModel.getNodeId());
			}
		}
		
		//Modification Date Tracking and Event posting:
		
		//if the user still has access of some kind, post a modification event (since only modified nodes get saved) as well as update the modification timestamp
		if(nodeModel.isDirectAccess() || nodeModel.isShoppingPeriodAdmin() || nodeModel.isAccessAdmin()){
			saveModifiedData(userId, nodeModel.getNodeId());
			sakaiProxy.postEvent(DelegatedAccessConstants.EVENT_MODIFIED_USER_PERMS, "/user/" + userId + "/node/" + nodeModel.getNodeId() + "/realm/" + nodeModel.getRealm() + "/role/" + nodeModel.getRole(), true);
		}
		
		//if the user added or removed direct access permissions, post an event
		if(nodeModel.isDirectAccess() != nodeModel.isDirectAccessOrig()){
			if(nodeModel.isDirectAccess()){
				sakaiProxy.postEvent(DelegatedAccessConstants.EVENT_ADD_USER_PERMS, "/user/" + userId + "/node/" + nodeModel.getNodeId() + "/realm/" + nodeModel.getRealm() + "/role/" + nodeModel.getRole(), true);
			}else{
				sakaiProxy.postEvent(DelegatedAccessConstants.EVENT_DELETE_USER_PERMS, "/user/" + userId + "/node/" + nodeModel.getNodeId(), true);
			}
		}
		//If ths user has been granted or removed shopping admin access, post an event and save the shopping modification timestamp
		//Theoretically the shopping period user would never be set to be a shopping period admin, but just in case someone tries, check for it
		if(!DelegatedAccessConstants.SHOPPING_PERIOD_USER.equals(userId) && nodeModel.isShoppingPeriodAdmin() != nodeModel.isShoppingPeriodAdminOrig()){
			saveShoppingPeriodAdminModifiedData(userId, nodeModel.getNodeId());
			if(nodeModel.isShoppingPeriodAdmin()){
				sakaiProxy.postEvent(DelegatedAccessConstants.EVENT_ADD_USER_SHOPPING_ADMIN, "/user/" + userId + "/node/" + nodeModel.getNodeId(), true);
			}else{
				sakaiProxy.postEvent(DelegatedAccessConstants.EVENT_DELETE_USER_SHOPPING_ADMIN, "/user/" + userId + "/node/" + nodeModel.getNodeId(), true);
			}
		}else if(!DelegatedAccessConstants.SHOPPING_PERIOD_USER.equals(userId) && nodeModel.getShoppingAdminModified() != null){
			//If there was no modification to shopping admin permission but there is a timestamp of previous modifications,
			//we need to resave it so we don't lose this information:
			hierarchyService.assignUserNodePerm(userId, nodeModel.getNodeId(), DelegatedAccessConstants.NODE_PERM_SHOPPING_ADMIN_MODIFIED + nodeModel.getShoppingAdminModified().getTime(), false);
			hierarchyService.assignUserNodePerm(userId, nodeModel.getNodeId(), DelegatedAccessConstants.NODE_PERM_SHOPPING_ADMIN_MODIFIED_BY + nodeModel.getShoppingAdminModifiedBy(), false);
		}
		if(nodeModel.isAccessAdmin() != nodeModel.isAccessAdminOrig()){
			if(nodeModel.isAccessAdmin()){
				sakaiProxy.postEvent(DelegatedAccessConstants.EVENT_ADD_USER_ACCESS_ADMIN, "/user/" + userId + "/node/" + nodeModel.getNodeId(), true);
			}else{
				sakaiProxy.postEvent(DelegatedAccessConstants.EVENT_DELETE_USER_ACCESS_ADMIN, "/user/" + userId + "/node/" + nodeModel.getNodeId(), true);
			}
		}
	}

	private void saveShoppingPeriodAdmin(boolean admin, String nodeId, String userId){
		//only save shopping period admin flag for real users
		if(admin && !DelegatedAccessConstants.SHOPPING_PERIOD_USER.equals(userId)){
			hierarchyService.assignUserNodePerm(userId, nodeId, DelegatedAccessConstants.NODE_PERM_SHOPPING_ADMIN, false);
		}
	}
	
	private void saveAccessAdmin(boolean accessAdmin, String nodeId, String userId){
		//only save shopping period admin flag for real users
		if(accessAdmin && !DelegatedAccessConstants.SHOPPING_PERIOD_USER.equals(userId)){
			hierarchyService.assignUserNodePerm(userId, nodeId, DelegatedAccessConstants.NODE_PERM_ACCESS_ADMIN, false);
		}
	}
	
	private void saveShoppingPeriodAdminModifiedData(String userId, String nodeId){
		hierarchyService.assignUserNodePerm(userId, nodeId, DelegatedAccessConstants.NODE_PERM_SHOPPING_ADMIN_MODIFIED + new Date().getTime(), false);
		hierarchyService.assignUserNodePerm(userId, nodeId, DelegatedAccessConstants.NODE_PERM_SHOPPING_ADMIN_MODIFIED_BY + sakaiProxy.getCurrentUserId(), false);
	}
	
	
	private void saveModifiedData(String userId, String nodeId){
		hierarchyService.assignUserNodePerm(userId, nodeId, DelegatedAccessConstants.NODE_PERM_MODIFIED + new Date().getTime(), false);
		hierarchyService.assignUserNodePerm(userId, nodeId, DelegatedAccessConstants.NODE_PERM_MODIFIED_BY + sakaiProxy.getCurrentUserId(), false);
	}

	private void saveShoppingPeriodAuth(String auth, String nodeId){
		if(auth != null && !"".equals(auth) && !"null".equals(auth)){
			hierarchyService.assignUserNodePerm(DelegatedAccessConstants.SHOPPING_PERIOD_USER, nodeId, DelegatedAccessConstants.NODE_PERM_SHOPPING_AUTH + auth, false);
		}
	}
	private void saveShoppingPeriodStartDate(Date startDate, String nodeId){
		if(startDate != null){
			hierarchyService.assignUserNodePerm(DelegatedAccessConstants.SHOPPING_PERIOD_USER, nodeId, DelegatedAccessConstants.NODE_PERM_SHOPPING_START_DATE + startDate.getTime(), false);
		}
	}
	private void saveShoppingPeriodEndDate(Date endDate, String nodeId){
		if(endDate != null){
			hierarchyService.assignUserNodePerm(DelegatedAccessConstants.SHOPPING_PERIOD_USER, nodeId, DelegatedAccessConstants.NODE_PERM_SHOPPING_END_DATE + endDate.getTime(), false);
		}
	}
		
	public void saveHierarchyJobLastRunDate(Date runDate, String nodeId){
		if(runDate != null){
			clearHierarchyJobLastRunDate(nodeId);
			hierarchyService.assignUserNodePerm(DelegatedAccessConstants.SITE_HIERARCHY_USER, nodeId, DelegatedAccessConstants.NODE_PERM_SITE_HIERARCHY_JOB_LAST_RUN_DATE + runDate.getTime(), false);
		}
	}
	private void clearHierarchyJobLastRunDate(String nodeId){
		for(String perm : hierarchyService.getPermsForUserNodes(DelegatedAccessConstants.SITE_HIERARCHY_USER, new String[]{nodeId})){
			if(perm.startsWith(DelegatedAccessConstants.NODE_PERM_SITE_HIERARCHY_JOB_LAST_RUN_DATE)){
				hierarchyService.removeUserNodePerm(DelegatedAccessConstants.SITE_HIERARCHY_USER, nodeId, perm, false);
			}	
		}
	}
	
	public Date getHierarchyJobLastRunDate(String nodeId){
		Date returnDate = null;
		for(String perm : hierarchyService.getPermsForUserNodes(DelegatedAccessConstants.SITE_HIERARCHY_USER, new String[]{nodeId})){
			if(perm.startsWith(DelegatedAccessConstants.NODE_PERM_SITE_HIERARCHY_JOB_LAST_RUN_DATE)){
				try{
					returnDate = new Date(Long.parseLong(perm.substring(DelegatedAccessConstants.NODE_PERM_SITE_HIERARCHY_JOB_LAST_RUN_DATE.length())));
				}catch (Exception e) {
					//wrong format, ignore
				}
			}	
		}
		return returnDate;
	}

	private void removeAllUserPermissions(String nodeId, String userId){
		for(String perm : getPermsForUserNodes(userId, nodeId)){
			hierarchyService.removeUserNodePerm(userId, nodeId, perm, false);
		}
	}

	/**
	 * returns a list of nodes the user has site.access permission (aka access).  Only direct nodes, nothing inherited.
	 * @return
	 */
	public Set<HierarchyNodeSerialized> getAllNodesForUser(String userId) {

		Set<HierarchyNodeSerialized> accessNodes = getAccessNodesForUser(userId);
		Set<HierarchyNodeSerialized> adminNodes = getShoppingPeriodAdminNodesForUser(userId);
		Set<HierarchyNodeSerialized> accessAdminNodes = getAccessAdminNodesForUser(userId);

		accessNodes.addAll(adminNodes);
		accessNodes.addAll(accessAdminNodes);
		return accessNodes;
	}

	public Set<HierarchyNodeSerialized> getAccessNodesForUser(String userId) {
		accessNodes = new ArrayList<String>();

		Set<HierarchyNodeSerialized> directAccessNodes = convertToSerializedNodeSet(hierarchyService.getNodesForUserPerm(userId, DelegatedAccessConstants.NODE_PERM_SITE_VISIT));
		//set the access and admin noes list for other functions to determine if a node is an access or admin node
		for(HierarchyNodeSerialized node : directAccessNodes){
			accessNodes.add(node.id);
		}
		return directAccessNodes;
	}

	public Set<HierarchyNodeSerialized> getShoppingPeriodAdminNodesForUser(String userId) {
		shoppingPeriodAdminNodes = new ArrayList<String>();

		Set<HierarchyNodeSerialized> adminNodes = convertToSerializedNodeSet(hierarchyService.getNodesForUserPerm(userId, DelegatedAccessConstants.NODE_PERM_SHOPPING_ADMIN));
		for(HierarchyNodeSerialized node : adminNodes){
			shoppingPeriodAdminNodes.add(node.id);
		}
		return adminNodes;
	}
	
	public Set<HierarchyNodeSerialized> getAccessAdminNodesForUser(String userId) {
		accessAdminNodes = new ArrayList<String>();

		Set<HierarchyNodeSerialized> adminNodes = convertToSerializedNodeSet(hierarchyService.getNodesForUserPerm(userId, DelegatedAccessConstants.NODE_PERM_ACCESS_ADMIN));
		for(HierarchyNodeSerialized node : adminNodes){
			accessAdminNodes.add(node.id);
		}
		return adminNodes;
	}
	/**
	 * returns a serialized version for Hierarchy nodes.
	 * 
	 * @param nodeSet
	 * @return
	 */
	private Set<HierarchyNodeSerialized> convertToSerializedNodeSet(Set<HierarchyNode> nodeSet){
		Set<HierarchyNodeSerialized> nodesForUserSerialized = new HashSet<HierarchyNodeSerialized>();
		if(nodeSet != null){
			for(HierarchyNode node : nodeSet){
				nodesForUserSerialized.add(new HierarchyNodeSerialized(node));
			}
		}
		return nodesForUserSerialized;
	}

	/**
	 * {@inheritDoc}
	 */
	public void initializeDelegatedAccessSession(){
		String userId = sakaiProxy.getCurrentUserId();
		if(userId != null && !"".equals(userId)){
			Session session = sakaiProxy.getCurrentSession();
			Set accessNodes = getAccessNodesForUser(userId);
			if(accessNodes != null && accessNodes.size() > 0){
				session.setAttribute(DelegatedAccessConstants.SESSION_ATTRIBUTE_DELEGATED_ACCESS_FLAG, true);
				//need to clear sakai realm permissions cache for user since Denied Tools list is tied to
				//session and permissions are a saved in a system cache
				Element el = restrictedToolsCache.get(userId);
				if(el != null){
					session.setAttribute(DelegatedAccessConstants.SESSION_ATTRIBUTE_DENIED_TOOLS, el.getObjectValue());
				}
			}
		}
	}

	private List<NodeModel> getSiteNodes(DefaultMutableTreeNode treeNode){
		List<NodeModel> returnList = new ArrayList<NodeModel>();
		if(treeNode != null){
			if(((NodeModel) treeNode.getUserObject()).getNode().title.startsWith("/site/")){
				returnList.add((NodeModel) treeNode.getUserObject());
			}
			//check the rest of the children:
			for(int i = 0; i < treeNode.getChildCount(); i++){
				returnList.addAll(getSiteNodes((DefaultMutableTreeNode)treeNode.getChildAt(i)));
			}
		}

		return returnList;
	}

	private HierarchyNodeSerialized getRootNode(){
		return new HierarchyNodeSerialized(hierarchyService.getRootNode(DelegatedAccessConstants.HIERARCHY_ID));
	}

	/**
	 * {@inheritDoc}
	 */
	public List<SearchResult> searchUsers(String search, int first, int last) {
		List<User> searchResult = sakaiProxy.searchUsers(search, first, last);
		List<SearchResult> returnList = new ArrayList<SearchResult>();
		for(User user : searchResult){
			returnList.add(getSearchResult(user));
		}

		return returnList;
	}

	/**
	 * {@inheritDoc}
	 */
	private SearchResult getSearchResult(User user){
		if(user != null){
			return new SearchResult(user);
		}else{
			return null;
		}
	}

	/**
	 * {@inheritDoc}
	 */
	private void saveRealmAndRoleAccess(String userId, String realmId, String role, String nodeId){
		if(realmId != null && role != null && !"".equals(realmId) && !"".equals(role) && !"null".equals(realmId) && !"null".equals(role)){
			hierarchyService.assignUserNodePerm(userId, nodeId, DelegatedAccessConstants.NODE_PERM_REALM_PREFIX +realmId, false);
			hierarchyService.assignUserNodePerm(userId, nodeId, DelegatedAccessConstants.NODE_PERM_ROLE_PREFIX +role, false);
		}
	}

	private Set<String> getPermsForUserNodes(String userId, String nodeId){
		return hierarchyService.getPermsForUserNodes(userId, new String[]{nodeId});
	}

	/**
	 * returns the user's realm and role information for the given node.  Doesn't include inherited information, will return
	 * a "" if not found.
	 * @param userId
	 * @param nodeId
	 * @return
	 */
	private String[] getAccessRealmRole(Set<String> perms){
		String realmId = "";
		String roleId = "";
		for(String perm : perms){
			if(perm.startsWith(DelegatedAccessConstants.NODE_PERM_REALM_PREFIX)){
				realmId = perm.substring(DelegatedAccessConstants.NODE_PERM_REALM_PREFIX.length());
			}else if(perm.startsWith(DelegatedAccessConstants.NODE_PERM_ROLE_PREFIX)){
				roleId = perm.substring(DelegatedAccessConstants.NODE_PERM_ROLE_PREFIX.length());
			}
		}
		return new String[]{realmId, roleId};
	}

	/**
	 * Returns a list of ToolSerialized that initialized the selected field
	 * @param userId
	 * @param nodeId
	 * @return
	 */
	public List<ListOptionSerialized> getRestrictedToolSerializedList(Set<String> perms){
		return getRestrictedToolSerializedList(perms, getEntireToolsList());
	}


	public List<ListOptionSerialized> getRestrictedToolSerializedList(Set<String> perms, List<ListOptionSerialized> blankList){
		List<String> restrictedTools = getRestrictedToolsForUser(perms);
		for(ListOptionSerialized tool : blankList){
			if(restrictedTools.contains(tool.getId()))
				tool.setSelected(true);
		}
		return blankList;
	}

	public List<ListOptionSerialized> getEntireToolsList(){
		List<ListOptionSerialized> returnList = new ArrayList<ListOptionSerialized>();
		for(Tool tool : sakaiProxy.getAllTools()){
			returnList.add(new ListOptionSerialized(tool.getId(), tool.getTitle() + " (" + tool.getId() + ")", false));
		}
		//the home tool is special, so add this case
		String[] homeTools = sakaiProxy.getHomeTools();
		if(homeTools != null && homeTools.length > 0){
			returnList.add(new ListOptionSerialized("Home", "Home", false));
		}
		Collections.sort(returnList, new Comparator<ListOptionSerialized>() {
			public int compare(ListOptionSerialized arg0, ListOptionSerialized arg1) {
				return arg0.getName().compareTo(arg1.getName());
			}
		});
		return returnList;
	}

	private List<String> getRestrictedToolsForUser(Set<String> userPerms){
		List<String> returnList = new ArrayList<String>();
		for(String userPerm : userPerms){
			if(userPerm.startsWith(DelegatedAccessConstants.NODE_PERM_DENY_TOOL_PREFIX)){
				returnList.add(userPerm.substring(DelegatedAccessConstants.NODE_PERM_DENY_TOOL_PREFIX.length()));
			}
		}
		return returnList;
	}

	private void saveRestrictedToolsForUser(String userId, String nodeId, List<String> toolIds){
		//add new tools:
		for(String newTool : toolIds){
			hierarchyService.assignUserNodePerm(userId, nodeId, DelegatedAccessConstants.NODE_PERM_DENY_TOOL_PREFIX + newTool, false);
		}
	}
	
	//terms
	public List<ListOptionSerialized> getTermSerializedList(Set<String> perms){
		return getTermSerializedList(perms, getEntireTermsList());
	}


	public List<ListOptionSerialized> getTermSerializedList(Set<String> perms, List<ListOptionSerialized> blankList){
		List<String> terms = getTermsForUser(perms);
		for(ListOptionSerialized term : blankList){
			if(terms.contains(term.getId()))
				term.setSelected(true);
		}
		return blankList;
	}

	public List<ListOptionSerialized> getEntireTermsList(){
		List<ListOptionSerialized> returnList = new ArrayList<ListOptionSerialized>();
		for(String[] term : sakaiProxy.getTerms()){
			returnList.add(new ListOptionSerialized(term[0], term[1], false));
		}
		return returnList;
	}

	private List<String> getTermsForUser(Set<String> userPerms){
		List<String> returnList = new ArrayList<String>();
		for(String userPerm : userPerms){
			if(userPerm.startsWith(DelegatedAccessConstants.NODE_PERM_TERM_PREFIX)){
				returnList.add(userPerm.substring(DelegatedAccessConstants.NODE_PERM_TERM_PREFIX.length()));
			}
		}
		return returnList;
	}

	private void saveTermsForUser(String userId, String nodeId, List<String> termIds){
		//add new tools:
		for(String newTerm : termIds){
			hierarchyService.assignUserNodePerm(userId, nodeId, DelegatedAccessConstants.NODE_PERM_TERM_PREFIX + newTerm, false);
		}
	}

	public List<SiteSearchResult> searchUserSites(String search, Map<String, String> advancedOptions, boolean shoppingPeriod, boolean activeShoppingData){
		List<SiteSearchResult> returnList = new ArrayList<SiteSearchResult>();
		if(search == null){
			search = "";
		}
		Collection<SiteSearchResult> siteSubset = null;
		Map<String, String> userSortNameCache = new HashMap<String, String>();
		if(!"".equals(search) || (advancedOptions != null && advancedOptions.size() > 0)){
			siteSubset = searchSites(search, advancedOptions);
			for(SiteSearchResult siteResult : siteSubset){
				AccessNode access = grantAccessToSite(siteResult.getSiteReference(), shoppingPeriod, activeShoppingData);
				if(access != null){
					siteResult.setAccess(access.getAccess());
					siteResult.setShoppingPeriodAuth(access.getAuth());
					siteResult.setShoppingPeriodStartDate(access.getStartDate());
					siteResult.setShoppingPeriodEndDate(access.getEndDate());
					siteResult.setRestrictedTools(access.getDeniedTools());
					siteResult.setModified(access.getModified());
					siteResult.setModifiedBy(access.getModifiedBy());
					if(!userSortNameCache.containsKey(access.getModifiedBy())){
						User user = sakaiProxy.getUser(access.getModifiedBy());
						String sortName = "";
						if(user != null){
							sortName = user.getSortName();
						}
						userSortNameCache.put(access.getModifiedBy(), sortName);
					}
					siteResult.setModifiedBySortName(userSortNameCache.get(access.getModifiedBy()));
					
					returnList.add(siteResult);
				}
			}
		}
		return returnList;
	}

	public Collection<SiteSearchResult> searchSites(String search, Map<String, String> advancedOptions){
		if("".equals(search)){
			search = null;
		}
		Map<String, SiteSearchResult> sites = new HashMap<String, SiteSearchResult>();
		Site searchByIdSite = sakaiProxy.getSiteById(search);
		String termField = sakaiProxy.getTermField();
				
		//get hierarchy structure:
		String[] hierarchy = sakaiProxy.getServerConfigurationStrings(DelegatedAccessConstants.HIERARCHY_SITE_PROPERTIES);
		if(hierarchy == null || hierarchy.length == 0){
			hierarchy = DelegatedAccessConstants.DEFAULT_HIERARCHY;
		}
		
		//Since we know the hierarchy is site properties, we can use them to speed up our search
		Map<String,String> propsMap = new HashMap<String, String>();
		for(String prop : hierarchy){
			propsMap.put(prop, "");
		}
		
		//add term field restriction if it exist:
		if (advancedOptions != null && advancedOptions.containsKey(DelegatedAccessConstants.ADVANCED_SEARCH_TERM)
				&& advancedOptions.get(DelegatedAccessConstants.ADVANCED_SEARCH_TERM) != null
				&& !"".equals(advancedOptions.get(DelegatedAccessConstants.ADVANCED_SEARCH_TERM).trim())) {
			//add term field to propMap for search
			propsMap.put(termField, advancedOptions.get(DelegatedAccessConstants.ADVANCED_SEARCH_TERM));
			//check if we need to remove the searchByIdSite b/c of the term
			if(searchByIdSite != null && searchByIdSite.getProperties() != null
					&& searchByIdSite.getProperties().getProperty(termField) != null
					&& searchByIdSite.getProperties().getProperty(termField).toLowerCase().contains(advancedOptions.get(DelegatedAccessConstants.ADVANCED_SEARCH_TERM))){
				//do nothing, we found it
			}else{
				//doesn't exist in this term, remove it
				searchByIdSite = null;
			}	
		}
		
		//add instructor restriction
		if (advancedOptions != null && advancedOptions.containsKey(DelegatedAccessConstants.ADVANCED_SEARCH_INSTRUCTOR)
				&& advancedOptions.get(DelegatedAccessConstants.ADVANCED_SEARCH_INSTRUCTOR) != null
				&& !"".equals(advancedOptions.get(DelegatedAccessConstants.ADVANCED_SEARCH_INSTRUCTOR).trim())) {
			List<User> searchUsers = sakaiProxy.searchUsers(advancedOptions.get(DelegatedAccessConstants.ADVANCED_SEARCH_INSTRUCTOR), 1, DelegatedAccessConstants.SEARCH_RESULTS_MAX);
			//since we added a site by searching for ID, we need to make sure that at least 1 user is a member,
			//otherwise, remove it from the results:
			boolean foundSearchByIdMember = searchByIdSite == null ? true : false;
			for (User user : searchUsers) {
				if(!foundSearchByIdMember && searchByIdSite.getMember(user.getId()) != null){
					foundSearchByIdMember = true;
				}
				//the search will use the propsMap (term if enabled) as well as search string, so no need to do additional searching after this
				for (Site site : getUserUpdatePermissionMembership(
						user.getId(), search, propsMap)) {
					if(sites.containsKey(site.getId())){
						sites.get(site.getId()).addInstructor(user);
					}else{
						List<User> usersList = new ArrayList<User>();
						usersList.add(user);
						sites.put(site.getId(), new SiteSearchResult(site, usersList, termField));
					}
				}
			}
			if(!foundSearchByIdMember && searchByIdSite != null){
				//we didn't find any members for this site in the user search, so remove it:
				searchByIdSite = null;
			}
		}else{
			// search title or id
			for (Site site : sakaiProxy.getSites(SelectionType.NON_USER, search,
					propsMap)) {
				sites.put(site.getId(), new SiteSearchResult(site, new ArrayList<User>(), termField));
			}
		}
		
		if(searchByIdSite != null && !sites.containsKey(searchByIdSite.getId())){
			sites.put(searchByIdSite.getId(), new SiteSearchResult(searchByIdSite, new ArrayList<User>(), termField));
		}
		
		
		return sites.values();
	}
	
	private List<Site> getUserUpdatePermissionMembership(String userId, String search, Map<String, String> propsMap){
		String currentUserId = sakaiProxy.getCurrentUserId();
		//set session user id to this id:
		Session sakaiSession = sakaiProxy.getCurrentSession();
		sakaiSession.setUserId(userId);
		sakaiSession.setUserEid(userId);
		List<Site> siteList = sakaiProxy.getSites(SelectionType.UPDATE, search, propsMap);
		//return to current user id
		sakaiSession.setUserId(currentUserId);
		sakaiSession.setUserEid(currentUserId);
		
		return siteList;
	}
	
	private String[] convertToArray(List<String> list){
		String[] returnArray = new String[]{};
		if(!list.isEmpty()){
			returnArray = new String[list.size()];
			for(int i = 0; i < list.size(); i++){
				returnArray[i] = list.get(i);
			}
		}
		return returnArray;
	}



	//TREE MODEL FUNCTIONS:


	private List<String> accessNodes = new ArrayList<String>();
	private List<String> shoppingPeriodAdminNodes = new ArrayList<String>();
	private List<String> accessAdminNodes = new ArrayList<String>();
	/**
	 * Creates the model that feeds the tree.
	 * 
	 * @return New instance of tree model.
	 */
	public TreeModel createEntireTreeModelForUser(String userId, boolean addDirectChildren, boolean cascade)
	{
		//these are the nodes that the user is allowed to assign
		Set<HierarchyNodeSerialized> accessAdminNodes = null;
		List<String> accessAdminNodeIds = null;
		//check if the user is a super admin, if not, then get the accessAdmin nodes connected to the current user
		if(!sakaiProxy.isSuperUser()){
			//only allow the current user to modify permissions for this user on the nodes that
			//has been assigned accessAdmin for currentUser
			accessAdminNodes = getAccessAdminNodesForUser(sakaiProxy.getCurrentUserId());
			accessAdminNodeIds = new ArrayList<String>();
			if(accessAdminNodes != null){
				for(HierarchyNodeSerialized node : accessAdminNodes){
					accessAdminNodeIds.add(node.id);
				}
			}
			//now call this function to set the nodeArrays:
			getAllNodesForUser(userId);
		}else{
			accessAdminNodes = getAllNodesForUser(userId);
		}
		
		//Returns a List that represents the tree/node architecture:
		//  List{ List{node, List<children>}, List{node, List<children>}, ...}.
		List<List> l1 = getTreeListForUser(userId, addDirectChildren, cascade, accessAdminNodes);
		//Remove the shopping period nodes:
		if(l1 != null){
			HierarchyNode shoppingRoot = hierarchyService.getRootNode(DelegatedAccessConstants.SHOPPING_PERIOD_HIERARCHY_ID);
			String shoppingPeriodRootId = "-1";
			if(shoppingRoot != null){
				shoppingPeriodRootId = shoppingRoot.id;
			}
			for (Iterator iterator = l1.iterator(); iterator.hasNext();) {
				List list = (List) iterator.next();
				if(shoppingPeriodRootId.equals(((HierarchyNodeSerialized) list.get(0)).id)){
					iterator.remove();
				}
			}
		}
		
		
		//order tree model:
		orderTreeModel(l1);

		return convertToTreeModel(l1, userId, getEntireToolsList(), getEntireTermsList(), addDirectChildren, accessAdminNodeIds);
	}

	public TreeModel createAccessTreeModelForUser(String userId, boolean addDirectChildren, boolean cascade)
	{
		//Returns a List that represents the tree/node architecture:
		//  List{ List{node, List<children>}, List{node, List<children>}, ...}.
		accessNodes = new ArrayList<String>();
		shoppingPeriodAdminNodes = new ArrayList<String>();
		accessAdminNodes = new ArrayList<String>();

		List<List> l1 = getTreeListForUser(userId, addDirectChildren, cascade, getAccessNodesForUser(userId));
		//order tree model:
		orderTreeModel(l1);

		return trimTreeForTerms(convertToTreeModel(l1, userId, getEntireToolsList(), getEntireTermsList(), addDirectChildren, null));
	}

	public TreeModel getTreeModelForShoppingPeriod(boolean includePerms){
		//Returns a List that represents the tree/node architecture:
		//  List{ List{node, List<children>}, List{node, List<children>}, ...}.
		String userId = "";
		if(includePerms){
			userId = DelegatedAccessConstants.SHOPPING_PERIOD_USER;
		}
		Set<HierarchyNodeSerialized> rootSet = new HashSet<HierarchyNodeSerialized>();
		rootSet.add(new HierarchyNodeSerialized(hierarchyService.getRootNode(DelegatedAccessConstants.SHOPPING_PERIOD_HIERARCHY_ID)));
		List<List> l1 = getTreeListForUser(userId, false, true, rootSet);
		//order tree model:
		orderTreeModel(l1);

		return convertToTreeModel(l1, userId, getEntireToolsList(), getEntireTermsList(), false, null);
	}
	
	//This will search through the tree model and trim out any sites that are restricted b/c of the term value
	private TreeModel trimTreeForTerms(TreeModel treeModel){
		//need a remove map b/c you can't remove nodes while you are searching, must do it afterwards
		Map<DefaultMutableTreeNode, List<DefaultMutableTreeNode>> removeMap = new HashMap<DefaultMutableTreeNode, List<DefaultMutableTreeNode>>();
		
		if(treeModel != null && treeModel.getRoot() != null){
			trimTreeForTermsHelper((DefaultMutableTreeNode) treeModel.getRoot(), null, removeMap);
		}
		//now remove everything that was found
		for(Entry<DefaultMutableTreeNode, List<DefaultMutableTreeNode>> entry : removeMap.entrySet()){
			DefaultMutableTreeNode removeParent = entry.getKey();
			for(DefaultMutableTreeNode removeNode : entry.getValue()){
				removeParent.remove(removeNode);
			}
		}
		return treeModel;
	}
	
	private void trimTreeForTermsHelper(DefaultMutableTreeNode node, DefaultMutableTreeNode parent, Map<DefaultMutableTreeNode, List<DefaultMutableTreeNode>> removeMap){
		if(node != null){
			for(int i = 0; i < node.getChildCount(); i++){
				trimTreeForTermsHelper((DefaultMutableTreeNode) node.getChildAt(i), node, removeMap);
			}
			NodeModel nodeModel = (NodeModel) node.getUserObject();
			if(nodeModel.getNode().title.startsWith("/site/")){
				String term = nodeModel.getNode().permKey;
				if(!checkTerm(nodeModel.getNodeTerms(), term)){
					if(parent != null){
						if(removeMap.containsKey(parent)){
							((List<DefaultMutableTreeNode>) removeMap.get(parent)).add(node);
						}else{
							List<DefaultMutableTreeNode> list = new ArrayList<DefaultMutableTreeNode>();
							list.add(node);
							removeMap.put(parent, list);
						}
					}
				}
			}

		}
	}

	private boolean checkTerm(String[] terms, String siteTerm){
		boolean returnVal = true;
		if(terms != null && terms.length > 0){
			returnVal = false;
			if(siteTerm != null && !"".equals(siteTerm)){
				for(String term : terms){
					if(term.equals(siteTerm)){
						returnVal = true;
						break;
					}
				}
			}
		}
		return returnVal;
	}
	//get the entire tree for a user and populates the information that may exist
	public TreeModel getEntireTreePlusUserPerms(String userId){
		//call this to instantiated the accessNodes and shoppingPeriodAdminNodes lists
		getAllNodesForUser(userId);
		//just get the root of the tree and then ask for all cascading nodes
		Set<HierarchyNodeSerialized> rootSet = new HashSet<HierarchyNodeSerialized>();
		rootSet.add(getRootNode());
		List<List> l1 = getTreeListForUser(userId, false, true, rootSet);
		//order tree model:
		orderTreeModel(l1);

		return convertToTreeModel(l1, userId, getEntireToolsList(), getEntireTermsList(), false, null);
	}

	public TreeModel createTreeModelForShoppingPeriod(String userId)
	{
		//Returns a List that represents the tree/node architecture:
		//  List{ List{node, List<children>}, List{node, List<children>}, ...}.

		List<List> l1 = getTreeListForUser(DelegatedAccessConstants.SHOPPING_PERIOD_USER, false, false, getShoppingPeriodAdminNodesForUser(userId));

		//order tree model:
		orderTreeModel(l1);

		return convertToTreeModel(l1, DelegatedAccessConstants.SHOPPING_PERIOD_USER, getEntireToolsList(), getEntireTermsList(), false, null);
	}

	/**
	 * Takes a list representation of a tree and creates the TreeModel
	 * 
	 * @param map
	 * @param userId
	 * @return
	 */
	private TreeModel convertToTreeModel(List<List> map, String userId, List<ListOptionSerialized> blankRestrictedTools, 
			List<ListOptionSerialized> blankTerms, boolean addDirectChildren, List<String> accessAdminNodeIds)
	{
		TreeModel model = null;
		if(!map.isEmpty() && map.size() == 1){

			DefaultMutableTreeNode rootNode = add(null, map, userId, blankRestrictedTools, blankTerms, addDirectChildren, accessAdminNodeIds);
			model = new DefaultTreeModel(rootNode);
		}
		return model;
	}

	private Date getShoppingStartDate(Set<String> perms){
		Date returnDate = null;
		for(String perm : perms){
			if(perm.startsWith(DelegatedAccessConstants.NODE_PERM_SHOPPING_START_DATE)){
				try{
					returnDate = new Date(Long.parseLong(perm.substring(DelegatedAccessConstants.NODE_PERM_SHOPPING_START_DATE.length())));
				}catch (Exception e) {
					//wrong format, ignore
				}
			}
		}

		return returnDate;
	}

	private Date getShoppingEndDate(Set<String> perms){
		Date returnDate = null;
		for(String perm : perms){
			if(perm.startsWith(DelegatedAccessConstants.NODE_PERM_SHOPPING_END_DATE)){
				try{
					returnDate = new Date(Long.parseLong(perm.substring(DelegatedAccessConstants.NODE_PERM_SHOPPING_END_DATE.length())));
				}catch (Exception e) {
					//wrong format, ignore
				}
			}
		}

		return returnDate;
	}

	private Date getPermDate(Set<String> perms, String permName){
		Date returnDate = null;
		for(String perm : perms){
			if(perm.startsWith(permName)){
				try{
					returnDate = new Date(Long.parseLong(perm.substring(permName.length())));
				}catch (Exception e) {
					//wrong format, ignore
				}
			}
		}
		return returnDate;
	}

	private String getShoppingPeriodAuth(Set<String> perms){
		for(String perm : perms){
			if(perm.startsWith(DelegatedAccessConstants.NODE_PERM_SHOPPING_AUTH)){
				return perm.substring(DelegatedAccessConstants.NODE_PERM_SHOPPING_AUTH.length());
			}
		}
		return "";
	}

	private String getShoppingAdminModifiedBy(Set<String> perms){
		for(String perm : perms){
			if(perm.startsWith(DelegatedAccessConstants.NODE_PERM_SHOPPING_ADMIN_MODIFIED_BY)){
				return perm.substring(DelegatedAccessConstants.NODE_PERM_SHOPPING_ADMIN_MODIFIED_BY.length());
			}
		}
		return null;
	}
	
	private String getModifiedBy(Set<String> perms){
		for(String perm : perms){
			if(perm.startsWith(DelegatedAccessConstants.NODE_PERM_MODIFIED_BY)){
				return perm.substring(DelegatedAccessConstants.NODE_PERM_MODIFIED_BY.length());
			}
		}
		return null;
	}
	
	
	private boolean isShoppingPeriodAdmin(Set<String> perms){
		for(String perm : perms){
			if(perm.startsWith(DelegatedAccessConstants.NODE_PERM_SHOPPING_ADMIN)){
				return true;
			}
		}
		return false;
	}

	private boolean getIsDirectAccess(Set<String> perms){
		for(String perm : perms){
			if(perm.equals(DelegatedAccessConstants.NODE_PERM_SITE_VISIT)){
				return true;
			}
		}
		return false;
	}
	
	private boolean getIsAccessAdmin(Set<String> perms){
		for(String perm : perms){
			if(perm.equals(DelegatedAccessConstants.NODE_PERM_ACCESS_ADMIN)){
				return true;
			}
		}
		return false;
	}

	private List<ListOptionSerialized> copyListOptions(List<ListOptionSerialized> options){
		List<ListOptionSerialized> returnList = new ArrayList<ListOptionSerialized>();
		for(ListOptionSerialized option : options){
			returnList.add(new ListOptionSerialized(option.getId(), option.getName(), option.isSelected()));
		}
		return returnList;
	}

	/**
	 * Adds node to parent and creates the NodeModel to store in the tree
	 * @param parent
	 * @param sub
	 * @param userId
	 * @return
	 */
	private DefaultMutableTreeNode add(DefaultMutableTreeNode parent, List<List> sub, String userId, List<ListOptionSerialized> blankRestrictedTools, 
			List<ListOptionSerialized> blankTerms, boolean addDirectChildren, List<String> accessAdminNodeIds)
	{
		DefaultMutableTreeNode root = null;
		for (List nodeList : sub)
		{
			HierarchyNodeSerialized node = (HierarchyNodeSerialized) nodeList.get(0);
			List children = (List) nodeList.get(1);
			String realm = "";
			String role = "";
			boolean directAccess = false;
			Date startDate = null;
			Date endDate = null;
			String shoppingPeriodAuth = "";
			Date shoppingAdminModified = null;
			String shoppingAdminModifiedBy = null;
			Date modified = null;
			String modifiedBy = null;
			
			//you must copy in order not to pass changes to other nodes
			List<ListOptionSerialized> restrictedTools = copyListOptions(blankRestrictedTools);
			List<ListOptionSerialized> terms = copyListOptions(blankTerms);
			boolean accessAdmin = accessAdminNodes.contains(node.id);
			boolean shoppingPeriodAdmin = shoppingPeriodAdminNodes.contains(node.id);
			if(DelegatedAccessConstants.SHOPPING_PERIOD_USER.equals(userId) || accessNodes.contains(node.id) || shoppingPeriodAdminNodes.contains(node.id)){
				Set<String> perms = getPermsForUserNodes(userId, node.id);
				String[] realmRole = getAccessRealmRole(perms);
				realm = realmRole[0];
				role = realmRole[1];
				startDate = getShoppingStartDate(perms);
				endDate = getShoppingEndDate(perms);
				shoppingPeriodAuth = getShoppingPeriodAuth(perms);
				restrictedTools = getRestrictedToolSerializedList(perms, restrictedTools);
				terms = getTermSerializedList(perms, terms);
				directAccess = getIsDirectAccess(perms);
				shoppingAdminModified = getPermDate(perms, DelegatedAccessConstants.NODE_PERM_SHOPPING_ADMIN_MODIFIED);
				shoppingAdminModifiedBy = getShoppingAdminModifiedBy(perms);
				modified = getPermDate(perms, DelegatedAccessConstants.NODE_PERM_MODIFIED);
				modifiedBy = getModifiedBy(perms);
			}
			NodeModel parentNodeModel = null;
			if(parent != null){
				parentNodeModel = ((NodeModel) parent.getUserObject());
			}
			DefaultMutableTreeNode child = new DelegatedAccessMutableTreeNode();
			NodeModel childNodeModel = new NodeModel(node.id, node, directAccess, realm, role, parentNodeModel, 
					restrictedTools, startDate, endDate, shoppingPeriodAuth, addDirectChildren && !children.isEmpty(), shoppingPeriodAdmin, terms, 
					modifiedBy, modified, shoppingAdminModified, shoppingAdminModifiedBy, accessAdmin);
			//this could be an accessAdmin modifying another user, let's check:
			if(accessAdminNodeIds != null){
				//if accessAdminNodeIds isn't null, this means we need to restrict this tree to these nodes by
				//setting the editable flag
				childNodeModel.setEditable(false);

				boolean found = false;
				for(String nodeId : accessAdminNodeIds){
					if(nodeId.equals(node.id)){
						found = true;
						break;
					}
				}
				if(found){
					childNodeModel.setEditable(true);
				}
			}
			child.setUserObject(childNodeModel);
			
			if(parent == null){
				//we have the root, set it
				root = child;
			}else{
				parent.add(child);
			}
			if(!children.isEmpty()){
				add(child, children, userId, blankRestrictedTools, blankTerms, addDirectChildren, accessAdminNodeIds);
			}
		}
		return root;
	}


	/**
	 * takes a list representation of the tree and orders it Alphabetically
	 * @param hierarchy
	 */
	private void orderTreeModel(List<List> hierarchy){
		if(hierarchy != null){
			for(List nodeList : hierarchy){
				orderTreeModel((List)nodeList.get(1));
			}
			Collections.sort(hierarchy, new NodeListComparator());
		}
	}

	/**
	 * This is a simple comparator to order the tree nodes alphabetically
	 *
	 */
	private class NodeListComparator implements Comparator<List>{
		public int compare(List o1, List o2) {
			return ((HierarchyNodeSerialized) o1.get(0)).title.compareToIgnoreCase(((HierarchyNodeSerialized) o2.get(0)).title);
		}
	}

	private List<List> getTreeListForUser(String userId, boolean addDirectChildren, boolean cascade, Set<HierarchyNodeSerialized> nodes){
		List<List> l1 = new ArrayList<List>();
		List<List> currentLevel = l1;

		for(HierarchyNodeSerialized node : nodes){
			for(String parentId : node.parentNodeIds){
				HierarchyNodeSerialized parentNode = getCachedNode(parentId);

				if(!hasNode(parentNode, currentLevel)){
					List newNode = new ArrayList();
					newNode.add(parentNode);
					newNode.add(new ArrayList());
					currentLevel.add(newNode);
				}
				currentLevel = getChildrenForNode(parentNode.id, currentLevel);
				if(addDirectChildren){
					for(List nodeList : getDirectChildren(parentNode)){
						if(!hasNode((HierarchyNodeSerialized) nodeList.get(0), currentLevel)){
							currentLevel.add(nodeList);
						}
					}
				}
			}
			if(!hasNode(node, currentLevel)){
				List child = new ArrayList();
				child.add(node);
				child.add(new ArrayList());
				currentLevel.add(child);
			}
			if(cascade){
				//we need to grab all children (children of children, ect) for this node since this an access node
				getCascadingChildren(node, getChildrenForNode(node.id, currentLevel));
			}
			currentLevel = l1;
		}
		if(l1.isEmpty() && addDirectChildren){
			//since we want direct children, include the root's direct children (when the node model is empty)
			HierarchyNodeSerialized root = getRootNode();
			if(root != null && root.id != null && !"".equals(root.id)){
				List child = new ArrayList();
				child.add(root);
				child.add(getDirectChildren(root));
				l1.add(child);
			}
		}

		return l1;
	}


	/**
	 * Checks nodeCache for node with given id.  If not found,
	 * looks up the node in the db and saves it in the cache
	 * 
	 * @param id
	 * @return
	 */
	private HierarchyNodeSerialized getCachedNode(String id){
		Element el = nodeCache.get(id);
		HierarchyNodeSerialized node = null;
		if(el == null){
			node = getNode(id);
			try{
				nodeCache.put(new Element(id, node));
			}catch (Exception e) {
				log.error("getCachedNode: " + id, e);
			}
		}else if(el.getObjectValue() instanceof HierarchyNodeSerialized){
			node = (HierarchyNodeSerialized) el.getObjectValue();
		}
		return node;
	}

	/**
	 * returns the children for this node
	 * 
	 * @param id
	 * @param level
	 * @return
	 */
	private List<List> getChildrenForNode(String id, List<List> level){
		for(List nodeList : level){
			HierarchyNodeSerialized n = (HierarchyNodeSerialized) nodeList.get(0);
			if(n.id.equals(id)){
				return (List<List>) nodeList.get(1);
			}
		}
		return null;
	}

	/**
	 * returns direct children for the parent.  Children will have empty lists.
	 * 
	 * @param parent
	 * @return
	 */
	private List<List> getDirectChildren(HierarchyNodeSerialized parent){
		List<List>returnList = new ArrayList<List>();

		if(parent != null){
			Set<String> parentChildren = parent.directChildNodeIds;
			for(String childId : parentChildren){
				List child = new ArrayList();
				child.add(getCachedNode(childId));
				child.add(new ArrayList());
				returnList.add(child);
			}
		}
		return returnList;
	}

	/**
	 * Finds all children of chilren and returns the hierarchy
	 * 
	 * @param parent
	 * @param children
	 * @return
	 */
	private List<List> getCascadingChildren(HierarchyNodeSerialized parent, List<List> children){
		Set<String> parentChildren = parent.directChildNodeIds;
		for(String childId : parentChildren){
			HierarchyNodeSerialized childNode = getCachedNode(childId);

			List childMap = getChildrenForNode(childNode.id, children);
			if(childMap == null){
				childMap = new ArrayList();
			}

			childMap = getCascadingChildren(childNode, childMap);
			if(!hasNode(childNode, children)){
				List childList = new ArrayList();
				childList.add(childNode);
				childList.add(childMap);
				children.add(childList);
			}
		}

		return children;
	}

	/**
	 * checks if the node exist in the list
	 * 
	 * @param node
	 * @param level
	 * @return
	 */
	private boolean hasNode(HierarchyNodeSerialized node, List<List> level){
		for(List nodeList : level){
			HierarchyNodeSerialized n = (HierarchyNodeSerialized) nodeList.get(0);
			if(n.id.equals(node.id)){
				return true;
			}
		}
		return false;
	}

	/**
	 * Adds children node to a node that hasn't had it's children populated.  This is used to increase the efficiency
	 * of the tree so you can create the structure on the fly with ajax
	 * 
	 * @param node
	 * @param userId
	 * @param blankRestrictedTools
	 * @param blankTerms
	 * @param onlyAccessNodes
	 * @param accessAdminNodes
	 * @return
	 */
	public boolean addChildrenNodes(Object node, String userId, List<ListOptionSerialized> blankRestrictedTools, List<ListOptionSerialized> blankTerms, boolean onlyAccessNodes, List<String> accessAdminNodes){
		boolean anyAdded = false;
		DefaultMutableTreeNode parentNode = (DefaultMutableTreeNode) node;
		NodeModel nodeModel = (NodeModel) ((DefaultMutableTreeNode) node).getUserObject();
		if(nodeModel.getNode() != null){
			List<List> childrenNodes = getDirectChildren(nodeModel.getNode());
			Collections.sort(childrenNodes, new NodeListComparator());
			for(List childList : childrenNodes){
				//check if the user can edit this node:
				if(accessAdminNodes != null && !((NodeModel) parentNode.getUserObject()).isNodeEditable()){
					//if accessAdmin nodes isn't null this means that the user is restricted to edit just those nodes and their children
					if(!accessAdminNodes.contains(((HierarchyNodeSerialized) childList.get(0)).id)){
						//since the parent node isn't editable and this node doesn't show up in the editable nodes list
						//we will not add this node
						continue;
					}
				}
				boolean newlyAdded = addChildNodeToTree((HierarchyNodeSerialized) childList.get(0), parentNode, userId, blankRestrictedTools, blankTerms, onlyAccessNodes);
				anyAdded = anyAdded || newlyAdded;
			}
		}
		return anyAdded;
	}

	/**
	 * This is a helper function for addChildrenNodes.  It will add the child nodes to the parent node and create the NodeModel.
	 * 
	 * @param childNode
	 * @param parentNode
	 * @param realmMap
	 * @param userId
	 * @return
	 */
	private boolean addChildNodeToTree(HierarchyNodeSerialized childNode, DefaultMutableTreeNode parentNode, String userId, List<ListOptionSerialized> blankRestrictedTools, List<ListOptionSerialized> blankTerms, boolean onlyAccessNodes){
		boolean added = false;
		if(!doesChildExist(childNode.id, parentNode)){
			//just create a blank child since the user should already have all the nodes with information in the db
			String realm = "";
			String role = "";
			boolean selected = false;
			Date startDate = null;
			Date endDate = null;
			String shoppingPeriodAuth = "";
			//you must copy to not pass changes to other nodes
			List<ListOptionSerialized> restrictedTools = copyListOptions(blankRestrictedTools);
			List<ListOptionSerialized> terms = copyListOptions(blankTerms);
			boolean shoppingPeriodAdmin = false;
			boolean directAccess = false;
			Date shoppingAdminModified = null;
			String shoppingAdminModifiedBy = null;
			Date modified = null;
			String modifiedBy = null;
			boolean accessAdmin = false;
			
			DefaultMutableTreeNode child = new DelegatedAccessMutableTreeNode();
			if(DelegatedAccessConstants.SHOPPING_PERIOD_USER.equals(userId)){
				Set<String> perms = getPermsForUserNodes(userId, childNode.id);
				String[] realmRole = getAccessRealmRole(perms);
				realm = realmRole[0];
				role = realmRole[1];
				startDate = getShoppingStartDate(perms);
				endDate = getShoppingEndDate(perms);
				shoppingPeriodAuth = getShoppingPeriodAuth(perms);
				restrictedTools = getRestrictedToolSerializedList(perms, restrictedTools);
				terms = getTermSerializedList(perms, terms);
				directAccess = getIsDirectAccess(perms);
				shoppingAdminModified = getPermDate(perms, DelegatedAccessConstants.NODE_PERM_SHOPPING_ADMIN_MODIFIED);
				shoppingAdminModifiedBy = getShoppingAdminModifiedBy(perms);
				modified = getPermDate(perms, DelegatedAccessConstants.NODE_PERM_MODIFIED);
				modifiedBy = getModifiedBy(perms);
				accessAdmin = getIsAccessAdmin(perms);
			}
			NodeModel node = new NodeModel(childNode.id, childNode, directAccess, realm, role,
					((NodeModel) parentNode.getUserObject()), restrictedTools, startDate, endDate, 
					shoppingPeriodAuth, false, shoppingPeriodAdmin, terms,
					modifiedBy, modified, shoppingAdminModified, shoppingAdminModifiedBy, accessAdmin);
			child.setUserObject(node);

			if(!onlyAccessNodes || node.getNodeAccess()){
				parentNode.add(child);
				added = true;
			}
		}
		return added;
	}

	/**
	 * Determines if the child exists in the tree structure.  This is a helper function for addChildNodeToTree to ensure 
	 * the duplicate child nodes aren't added
	 * 
	 * @param childNodeId
	 * @param parentNode
	 * @return
	 */
	private boolean doesChildExist(String childNodeId, DefaultMutableTreeNode parentNode){
		boolean exists = false;

		for(int i = 0; i < parentNode.getChildCount(); i++){
			DefaultMutableTreeNode child = (DefaultMutableTreeNode) parentNode.getChildAt(i);
			if(childNodeId.equals(((NodeModel) child.getUserObject()).getNodeId())){
				exists = true;
				break;
			}
		}

		return exists;
	}

	public NodeModel getNodeModel(String nodeId, String userId){
		HierarchyNodeSerialized node = getNode(nodeId);
		NodeModel parentNodeModel = null;
		if(node.parentNodeIds != null && node.parentNodeIds.size() > 0){
			//grad the last parent in the Set (this is the closest parent)
			parentNodeModel = getNodeModel((String) node.parentNodeIds.toArray()[node.parentNodeIds.size() -1], userId);
		}
		Set<String> nodePerms = hierarchyService.getPermsForUserNodes(userId, new String[]{nodeId});
		Set<String> perms = getPermsForUserNodes(userId, node.id);
		String[] realmRole = getAccessRealmRole(perms);
		String realm = realmRole[0];
		String role = realmRole[1];
		Date startDate = getShoppingStartDate(perms);
		Date endDate = getShoppingEndDate(perms);
		String shoppingPeriodAuth = getShoppingPeriodAuth(perms);
		List<ListOptionSerialized> restrictedTools = getRestrictedToolSerializedList(perms, getEntireToolsList());
		List<ListOptionSerialized> terms = getTermSerializedList(perms, getEntireToolsList());
		boolean direct = getIsDirectAccess(perms);
		boolean shoppingPeriodAdmin = isShoppingPeriodAdmin(perms);
		boolean accessAdmin = getIsAccessAdmin(perms);
		Date shoppingAdminModified = getPermDate(perms, DelegatedAccessConstants.NODE_PERM_SHOPPING_ADMIN_MODIFIED);
		String shoppingAdminModifiedBy = getShoppingAdminModifiedBy(perms);
		Date modified = getPermDate(perms, DelegatedAccessConstants.NODE_PERM_MODIFIED);
		String modifiedBy = getModifiedBy(perms);
		
		NodeModel nodeModel = new NodeModel(node.id, node, getIsDirectAccess(nodePerms),
				realm, role, parentNodeModel, restrictedTools, startDate, endDate, shoppingPeriodAuth, false, shoppingPeriodAdmin, terms,
				modifiedBy, modified, shoppingAdminModified, shoppingAdminModifiedBy, accessAdmin);
		return nodeModel;
	}

	/**
	 * {@inheritDoc}
	 */
	public void assignUserNodePerm(String userId, String nodeId, String perm, boolean cascade) {		
		hierarchyService.assignUserNodePerm(userId, nodeId, perm, false);
	}
	
	public void removeNode(String nodeId){
		removeNode(hierarchyService.getNodeById(nodeId));
	}
	
	public void removeNode(HierarchyNode node){
		if(node != null){
			if(node.childNodeIds != null && !node.childNodeIds.isEmpty()){
				//we can delete this, otherwise, delete the children first the children
				for(String childId : node.childNodeIds){		
					removeNode(hierarchyService.getNodeById(childId));
				}
			}
			//all the children nodes have been deleted, now its safe to delete
			hierarchyService.removeNode(node.id);
			Set<String> userIds = hierarchyService.getUserIdsForNodesPerm(new String[]{node.id}, DelegatedAccessConstants.NODE_PERM_SITE_VISIT);
			for(String userId : userIds){
				removeAllUserPermissions(node.id, userId);
			}
			//since the hierarchy service doesn't really delete the nodes,
			//we need to distinguish between deleted nodes
			hierarchyService.setNodeDisabled(node.id, true);
		}
	}
	
	public void deleteEmptyNonSiteNodes(String hierarchyId){
		List<String> emptyNodes = dao.getEmptyNonSiteNodes(hierarchyId);
		//I don't like loops, loops shouldn't happen but never say never
		int loopProtection = 1;
		while(emptyNodes != null && emptyNodes.size() > 0 && loopProtection < 1000000){
			for(String id : emptyNodes){
				removeNode(hierarchyService.getNodeById(id));
			}
			//check again
			emptyNodes = dao.getEmptyNonSiteNodes(hierarchyId);
			loopProtection++;
		}
	}
	public Map<String, String> getRealmRoleDisplay(boolean shopping){
		if(shopping){
			return convertRealmRoleToSingleList(sakaiProxy.getShoppingRealmOptions());
		}else{
			return convertRealmRoleToSingleList(sakaiProxy.getDelegatedAccessRealmOptions());
		}
	}
	
	private Map<String, String> convertRealmRoleToSingleList(Map<String, List<String>> realmMap){
		//First get a list of all roles:
		List<String> allRoles = new ArrayList<String>();
		for(Entry<String, List<String>> entry : realmMap.entrySet()){
			for(String role : entry.getValue()){
				allRoles.add(role);
			}
		}
		//convert this map to a single role dropdown representation:
		Map<String, String> returnMap = new HashMap<String, String>();
		for(Entry<String, List<String>> entry : realmMap.entrySet()){
			String realm = entry.getKey();
			for(String role : entry.getValue()){
				String roleTitle = role;
				if(countNumOfOccurances(allRoles, role) > 1){
					roleTitle += " (" + realm + ")";
				}
				returnMap.put(realm + ":" + role, roleTitle);
			}
		}

		return returnMap;
	}
	
	private int countNumOfOccurances(List<String> list, String str){
		int i = 0;
		for(String check : list){
			if(check.equals(str)){
				i++;
			}
		}
		return i;
	}
	
	public List<ListOptionSerialized> getAuthorizationOptions(){
		List<ListOptionSerialized> returnList = new ArrayList<ListOptionSerialized>();
		returnList.add(new ListOptionSerialized(".auth", "Logged In", false));
		returnList.add(new ListOptionSerialized(".anon", "Public", false));
		return returnList;
	}
	
	public boolean hasShoppingPeriodAdminNodes(String userId){
		if(userId == null || "".equals(userId)){
			return false;
		}
		Set<HierarchyNode> shoppingAdminNodes = hierarchyService.getNodesForUserPerm(userId, DelegatedAccessConstants.NODE_PERM_SHOPPING_ADMIN); 
		return shoppingAdminNodes != null && shoppingAdminNodes.size() > 0;
	}
	
	public boolean hasDelegatedAccessNodes(String userId){
		if(userId == null || "".equals(userId)){
			return false;
		}
		Set<HierarchyNode> delegatedAccessNodes = hierarchyService.getNodesForUserPerm(userId, DelegatedAccessConstants.NODE_PERM_SITE_VISIT); 
		return delegatedAccessNodes != null && delegatedAccessNodes.size() > 0;
	}
	
	public boolean hasAccessAdminNodes(String userId){
		if(userId == null || "".equals(userId)){
			return false;
		}
		Set<HierarchyNode> accessAdminNodes = hierarchyService.getNodesForUserPerm(userId, DelegatedAccessConstants.NODE_PERM_ACCESS_ADMIN); 
		return accessAdminNodes != null && accessAdminNodes.size() > 0;
	}
	
	public List<String> getNodesBySiteRef(String siteRef, String hierarchyId){
		return dao.getNodesBySiteRef(siteRef, hierarchyId);
	}
	
	public void clearNodeCache(){
		nodeCache.removeAll();
	}
	
	public String[] getCurrentUsersAccessToSite(String siteRef){
		//check the session first:
		if(sakaiProxy.getCurrentSession().getAttribute(DelegatedAccessConstants.SESSION_ATTRIBUTE_ACCESS_MAP) != null 
				&& ((Map) sakaiProxy.getCurrentSession().getAttribute(DelegatedAccessConstants.SESSION_ATTRIBUTE_ACCESS_MAP)).containsKey(siteRef)){
			return (String[]) ((Map) sakaiProxy.getCurrentSession().getAttribute(DelegatedAccessConstants.SESSION_ATTRIBUTE_ACCESS_MAP)).get(siteRef);
		}else{
			AccessNode access = grantAccessToSite(siteRef, false, false);
			if(access != null){
				return access.getAccess();
			}else{
				return null;
			}
		}
	}
	
	private AccessNode grantAccessToSite(String siteRef, boolean shoppingPeriod, boolean activeShoppingData){
		AccessNode returnNode = null;
		Session session = sakaiProxy.getCurrentSession();
		Map<String, String[]> deniedToolsMap = new HashMap<String, String[]>();
		if(!shoppingPeriod){
			//only worry about the session for non shopping period queries
			Object sessionDeniedToolsMap = session.getAttribute(DelegatedAccessConstants.SESSION_ATTRIBUTE_DENIED_TOOLS);
			if(sessionDeniedToolsMap != null){
				deniedToolsMap = (Map<String, String[]>) sessionDeniedToolsMap;
			}
		}

		Map<String, String[]> accessMap = new HashMap<String, String[]>();
		if(!shoppingPeriod){
			//only worry about the session for non shopping period queries
			Object sessionaccessMap = session.getAttribute(DelegatedAccessConstants.SESSION_ATTRIBUTE_ACCESS_MAP);
			if(sessionaccessMap != null){
				accessMap = (Map<String, String[]>) sessionaccessMap;
			}
		}
		
		if(!shoppingPeriod && accessMap.containsKey(siteRef) && accessMap.get(siteRef) == null){
			//we already know this result is null, so return null
			return null;
		}
		
		//set default to no access and override it if the user does have access
		//this is so we don't have to keep looking up their access for the same site:
		deniedToolsMap.put(siteRef, null);
		accessMap.put(siteRef, null);

		String userId = sakaiProxy.getCurrentUserId();
		if(shoppingPeriod){
			userId = DelegatedAccessConstants.SHOPPING_PERIOD_USER;
		}

		//this is a simple flag set in the delegated access login observer which
		//determines if there is a need to lookup access information for this user.
		//if it's not set, then don't worry about looking up anything
		Object dAMapFlag = null;
		if(!shoppingPeriod){
			dAMapFlag = session.getAttribute(DelegatedAccessConstants.SESSION_ATTRIBUTE_DELEGATED_ACCESS_FLAG);
		}
		boolean isUserMember = sakaiProxy.isUserMember(userId, siteRef);
		if((dAMapFlag != null && !isUserMember) || shoppingPeriod){
			//find the node for the site
			List<String> siteNodes = getNodesBySiteRef(siteRef, DelegatedAccessConstants.HIERARCHY_ID);
			if(siteNodes != null && siteNodes.size() == 1){
				//find the first access node for this user, if none found, then that means they don't have access
				String nodeId = siteNodes.get(0);
				while(nodeId != null && !"".equals(nodeId)){
					//get user's permissions for this site/node
					Set<String> perms = hierarchyService.getPermsForUserNodes(userId, new String[]{nodeId});
					//is this node direct access (checked), if so, grab the settings, otherwise, look at the parent
					if(getIsDirectAccess(perms)){
						if(shoppingPeriod && activeShoppingData){
							//do substring(6) b/c we need site ID and what is stored is a ref: /site/1231231
							String siteId = siteRef.substring(6);
							if(!isShoppingAvailable(perms, siteId)){
								//check that shopping period is still available unless activeShoppingData is false
								break;
							}
						}
						//Access Map:
						String[] access = getAccessRealmRole(perms);
						if (access == null || access.length != 2
								|| access[0] == null
								|| access[1] == null
								|| "".equals(access[0])
								|| "".equals(access[1])
								|| "null".equals(access[0])
								|| "null".equals(access[1])) {
							access = new String[]{"", ""};
						}

						accessMap.put(siteRef, access);
						
						//Denied Tools List
						List<String> deniedTools = getRestrictedToolsForUser(perms);
						String[] deniedToolsArr = (String[]) deniedTools.toArray(new String[deniedTools.size()]);
						if(deniedToolsArr != null){
							deniedToolsMap.put(siteRef, deniedToolsArr);
						}else{
							deniedToolsMap.put(siteRef, new String[0]);
						}
						

						String shoppingAuth = getShoppingPeriodAuth(perms);
						
						Date startDate = getShoppingStartDate(perms);
						Date endDate = getShoppingEndDate(perms);
						Date modified = getPermDate(perms, DelegatedAccessConstants.NODE_PERM_MODIFIED);
						String modifiedBy = getModifiedBy(perms);
						
						//set returnNode
						returnNode = new AccessNode(siteRef, access, deniedToolsArr, shoppingAuth, startDate, endDate, modified, modifiedBy);

						//break out of loop
						nodeId = null;
						break;
					}else{
						Set<String> parentIds = hierarchyService.getNodeById(nodeId).directParentNodeIds;
						nodeId = null;
						if(parentIds != null && parentIds.size() == 1){
							for(String id : parentIds){
								nodeId = id;
							}
						}
					}
				}
			}
		}
		if(!shoppingPeriod){
			//we want to set the session map no matter what so we don't have to look it up again:
			session.setAttribute(DelegatedAccessConstants.SESSION_ATTRIBUTE_DENIED_TOOLS, deniedToolsMap);
			session.setAttribute(DelegatedAccessConstants.SESSION_ATTRIBUTE_ACCESS_MAP, accessMap);
			//update restrictedToolsCache
			try{
				restrictedToolsCache.put(new Element(userId, deniedToolsMap));
			}catch (Exception e) {
				log.error("grantAccessToSite: " + siteRef + ", " + userId, e);
			}
		}
		
		return returnNode;
	}
	
	private boolean isShoppingAvailable(Set<String> perms, String siteId){
		Date startDate = getShoppingStartDate(perms);
		Date endDate = getShoppingEndDate(perms);
		String[] nodeAccessRealmRole = getAccessRealmRole(perms);
		String auth = getShoppingPeriodAuth(perms);
		List<String> termsArr = getTermsForUser(perms);
		String siteTerm = null;
		String [] terms = null;
		if(terms != null){
			terms = termsArr.toArray(new String[termsArr.size()]);
			siteTerm = dao.getSiteProperty(sakaiProxy.getTermField(), siteId);
		}
		return isShoppingPeriodOpenForSite(startDate, endDate, nodeAccessRealmRole, auth, terms, siteId);
	}
	
	public boolean isShoppingPeriodOpenForSite(Date startDate, Date endDate, String[] nodeAccessRealmRole, String auth, String[] terms, String siteTerm){
		Date now = new Date();
		boolean isOpen = false;
		if(startDate != null && endDate != null){
			isOpen = startDate.before(now) && endDate.after(now);
		}else if(startDate != null){
			isOpen = startDate.before(now);
		}else if(endDate != null){
			isOpen = endDate.after(now);
		}
		if(nodeAccessRealmRole != null && nodeAccessRealmRole.length == 2 && !"".equals(nodeAccessRealmRole[0]) && !"".equals(nodeAccessRealmRole[1])
				&& !"null".equals(nodeAccessRealmRole[0]) && !"null".equals(nodeAccessRealmRole[1])){
			isOpen = isOpen && true;
		}else{
			isOpen = false;
		}
		if(auth == null || "".equals(auth)){
			isOpen = false;
		}else if(".anon".equals(auth) || ".auth".equals(auth)){
			isOpen = isOpen && true;
		}
		 if(!checkTerm(terms, siteTerm)){
			 isOpen = false;
		 }
		
		return isOpen;
	}
	
	
	private class AccessNode{
		private String siteRef;
		private String[] access;
		private String[] deniedTools;
		private String auth;
		private Date startDate;
		private Date endDate;
		private Date modified;
		private String modifiedBy;
		
		public AccessNode(String siteRef, String[] access, String[] deniedTools, String auth, Date startDate,
				Date endDate, Date modified, String modifiedBy){
			this.siteRef = siteRef;
			this.access = access;
			this.deniedTools = deniedTools;
			this.setAuth(auth);
			this.setStartDate(startDate);
			this.setEndDate(endDate);
			this.setModified(modified);
			this.setModifiedBy(modifiedBy);
		}

		public void setSiteRef(String siteRef) {
			this.siteRef = siteRef;
		}

		public String getSiteRef() {
			return siteRef;
		}

		public void setAccess(String[] access) {
			this.access = access;
		}

		public String[] getAccess() {
			return access;
		}

		public void setDeniedTools(String[] deniedTools) {
			this.deniedTools = deniedTools;
		}

		public String[] getDeniedTools() {
			return deniedTools;
		}

		public void setAuth(String auth) {
			this.auth = auth;
		}

		public String getAuth() {
			return auth;
		}

		public void setStartDate(Date startDate) {
			this.startDate = startDate;
		}

		public Date getStartDate() {
			return startDate;
		}

		public void setEndDate(Date endDate) {
			this.endDate = endDate;
		}

		public Date getEndDate() {
			return endDate;
		}

		public Date getModified() {
			return modified;
		}

		public void setModified(Date modified) {
			this.modified = modified;
		}

		public String getModifiedBy() {
			return modifiedBy;
		}

		public void setModifiedBy(String modifiedBy) {
			this.modifiedBy = modifiedBy;
		}
	}
}
