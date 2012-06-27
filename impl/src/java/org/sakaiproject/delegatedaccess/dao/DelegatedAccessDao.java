package org.sakaiproject.delegatedaccess.dao;

import java.util.List;
import java.util.Map;

public interface DelegatedAccessDao {

	public List<String> getDistinctSiteTerms(String termField);
	
	public String getSiteProperty(String propertyName, String siteId);
	
	public void updateSiteProperty(String siteId, String propertyName, String propertyValue);
	
	public void addSiteProperty(String siteId, String propertyName, String propertyValue);
	
	public void removeSiteProperty(String siteId, String propertyName);
	
	/**
	 * returns a Map of -> {siteRef, nodeId}
	 * 
	 * @param siteRef
	 * @param hierarchyId
	 * @return
	 */
	public Map<String, String> getNodesBySiteRef(String[] siteRef, String hierarchyId);
	
	public List<String> getEmptyNonSiteNodes(String hierarchyId);
}
