package org.openmrs.module.auditlog.api.db.hibernate.interceptor;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.CallbackException;
import org.hibernate.EmptyInterceptor;
import org.hibernate.EntityMode;
import org.hibernate.Interceptor;
import org.hibernate.Transaction;
import org.hibernate.collection.PersistentCollection;
import org.hibernate.collection.PersistentMap;
import org.hibernate.metadata.ClassMetadata;
import org.hibernate.type.StringType;
import org.hibernate.type.TextType;
import org.hibernate.type.Type;
import org.openmrs.OpenmrsObject;
import org.openmrs.User;
import org.openmrs.api.context.Context;
import org.openmrs.module.auditlog.AuditLog;
import org.openmrs.module.auditlog.AuditLog.Action;
import org.openmrs.module.auditlog.api.db.AuditLogDAO;
import org.openmrs.module.auditlog.util.AuditLogConstants;
import org.openmrs.module.auditlog.util.AuditLogUtil;
import org.openmrs.util.OpenmrsUtil;
import org.openmrs.util.Reflect;
import org.springframework.beans.BeanUtils;

/**
 * A hibernate {@link Interceptor} implementation, intercepts any database inserts, updates and
 * deletes and creates audit log entries for Monitored Objects, it logs changes for a single session
 * meaning that if User A and B concurrently make changes to the same object, there will be 2 log
 * entries in the DB, one for each user's session. Any changes/inserts/deletes made to the DB that
 * are not made through the application won't be detected by the module.
 */
public class HibernateAuditLogInterceptor extends EmptyInterceptor {
	
	private static final long serialVersionUID = 1L;
	
	private static final Log log = LogFactory.getLog(HibernateAuditLogInterceptor.class);
	
	private ThreadLocal<HashSet<OpenmrsObject>> inserts = new ThreadLocal<HashSet<OpenmrsObject>>();
	
	private ThreadLocal<HashSet<OpenmrsObject>> updates = new ThreadLocal<HashSet<OpenmrsObject>>();
	
	private ThreadLocal<HashSet<OpenmrsObject>> deletes = new ThreadLocal<HashSet<OpenmrsObject>>();
	
	//Mapping between object uuids and maps of its changed property names and their older values, the first item in the array is the old value while the the second is the new value
	private ThreadLocal<Map<String, Map<String, String[]>>> objectChangesMap = new ThreadLocal<Map<String, Map<String, String[]>>>();
	
	//Mapping between entities and lists of their Collections in the current session
	private ThreadLocal<Map<Object, List<Collection<?>>>> entityCollectionsMap = new ThreadLocal<Map<Object, List<Collection<?>>>>();
	
	private ThreadLocal<Date> date = new ThreadLocal<Date>();
	
	private AuditLogDAO auditLogDao;
	
	//Ignore these properties because they match auditLog.user and auditLog.dateCreated
	//TODO Should we not ignore personDateChanged and personDateChangedBy?
	private static final String[] IGNORED_PROPERTIES = new String[] { "changedBy", "dateChanged", "personDateChangedBy",
	        "personDateChanged", "creator", "dateCreated", "voidedBy", "dateVoided", "retiredBy", "dateRetired" };
	
	/**
	 * @return the dao
	 */
	public AuditLogDAO getAuditLogDao() {
		if (auditLogDao == null)
			auditLogDao = Context.getRegisteredComponents(AuditLogDAO.class).get(0);
		
		return auditLogDao;
	}
	
	/**
	 * @see org.hibernate.EmptyInterceptor#afterTransactionBegin(org.hibernate.Transaction)
	 */
	@Override
	public void afterTransactionBegin(Transaction tx) {
		inserts.set(new HashSet<OpenmrsObject>());
		updates.set(new HashSet<OpenmrsObject>());
		deletes.set(new HashSet<OpenmrsObject>());
		objectChangesMap.set(new HashMap<String, Map<String, String[]>>());
		entityCollectionsMap.set(new HashMap<Object, List<Collection<?>>>());
		date.set(new Date());
	}
	
	/**
	 * @see org.hibernate.EmptyInterceptor#onSave(java.lang.Object, java.io.Serializable,
	 *      java.lang.Object[], java.lang.String[], org.hibernate.type.Type[])
	 */
	@Override
	public boolean onSave(Object entity, Serializable id, Object[] state, String[] propertyNames, Type[] types) {
		if (isAuditable(entity)) {
			OpenmrsObject openmrsObject = (OpenmrsObject) entity;
			if (log.isDebugEnabled())
				log.debug("Creating log entry for created object with uuid:" + openmrsObject.getUuid() + " of type:"
				        + entity.getClass().getName());
			
			inserts.get().add(openmrsObject);
		}
		
		return false;
	}
	
	/**
	 * @see org.hibernate.EmptyInterceptor#onFlushDirty(java.lang.Object, java.io.Serializable,
	 *      java.lang.Object[], java.lang.Object[], java.lang.String[], org.hibernate.type.Type[])
	 */
	@Override
	public boolean onFlushDirty(Object entity, Serializable id, Object[] currentState, Object[] previousState,
	                            String[] propertyNames, Type[] types) {
		
		if (propertyNames != null && isAuditable(entity)) {
			OpenmrsObject openmrsObject = (OpenmrsObject) entity;
			Map<String, String[]> propertyChangesMap = null;//Map<propertyName, Object[]{currentValue, PreviousValue}>
			for (int i = 0; i < propertyNames.length; i++) {
				//we need to ignore dateChanged and changedBy fields in any case they
				//are actually part of the Auditlog in form of user and dateCreated
				if (ArrayUtils.contains(IGNORED_PROPERTIES, propertyNames[i]))
					continue;
				
				Object previousValue = (previousState != null) ? previousState[i] : null;
				Object currentValue = (currentState != null) ? currentState[i] : null;
				Class<?> propertyType = types[i].getReturnedClass();
				//TODO We need to handle time zones issues better
				if (!Reflect.isCollection(propertyType) && !OpenmrsUtil.nullSafeEquals(currentValue, previousValue)) {
					//For string properties, ignore changes from null to blank and vice versa
					//TODO This should be user configurable via a module GP
					if (StringType.class.getName().equals(types[i].getClass().getName())
					        || TextType.class.getName().equals(types[i].getClass().getName())) {
						String currentStateString = null;
						if (currentValue != null && !StringUtils.isBlank(currentValue.toString()))
							currentStateString = currentValue.toString();
						
						String previousValueString = null;
						if (previousValue != null && !StringUtils.isBlank(previousValue.toString()))
							previousValueString = previousValue.toString();
						
						//TODO Case sensibility here should be configurable via a GP by admin
						if (OpenmrsUtil.nullSafeEqualsIgnoreCase(previousValueString, currentStateString))
							continue;
					}
					
					if (propertyChangesMap == null)
						propertyChangesMap = new HashMap<String, String[]>();
					
					String flattenedPreviousValue = "";
					String flattenedCurrentValue = "";
					
					if (BeanUtils.isSimpleValueType(propertyType)) {
						if (Date.class.isAssignableFrom(propertyType)) {
							if (previousValue != null) {
								flattenedPreviousValue = new SimpleDateFormat(AuditLogConstants.DATE_FORMAT)
								        .format(previousValue);
							}
							if (currentValue != null) {
								flattenedCurrentValue = new SimpleDateFormat(AuditLogConstants.DATE_FORMAT)
								        .format(currentValue);
							}
						} else if (Enum.class.isAssignableFrom(propertyType)) {
							//Use value.name() over value.toString() to ensure we always get back the enum 
							//constant value and not the value returned by the implementation of value.toString()
							if (previousValue != null)
								flattenedPreviousValue = ((Enum<?>) previousValue).name();
							if (currentValue != null)
								flattenedCurrentValue = ((Enum<?>) currentValue).name();
						} else if (Class.class.isAssignableFrom(propertyType)) {
							if (previousValue != null)
								flattenedPreviousValue = ((Class<?>) previousValue).getName();
							if (currentValue != null)
								flattenedCurrentValue = ((Class<?>) currentValue).getName();
						} else {
							if (previousValue != null)
								flattenedPreviousValue = previousValue.toString();
							if (currentValue != null)
								flattenedCurrentValue = currentValue.toString();
						}
					} else if (types[i].isAssociationType() && !types[i].isCollectionType()) {
						//this is an association, store the primary key value
						if (OpenmrsObject.class.isAssignableFrom(propertyType)) {
							if (previousValue != null) {
								flattenedPreviousValue = AuditLogConstants.UUID_LABEL
								        + ((OpenmrsObject) previousValue).getUuid();
							}
							if (currentValue != null) {
								flattenedCurrentValue = AuditLogConstants.UUID_LABEL
								        + ((OpenmrsObject) currentValue).getUuid();
							}
						} else {
							ClassMetadata metadata = getAuditLogDao().getClassMetadata(propertyType);
							if (previousValue != null && metadata.getIdentifier(previousValue, EntityMode.POJO) != null) {
								flattenedPreviousValue = AuditLogConstants.ID_LABEL
								        + metadata.getIdentifier(previousValue, EntityMode.POJO).toString();
							}
							if (currentValue != null && metadata.getIdentifier(currentValue, EntityMode.POJO) != null) {
								flattenedCurrentValue = AuditLogConstants.ID_LABEL
								        + metadata.getIdentifier(currentValue, EntityMode.POJO).toString();
							}
						}
					} else if (types[i].isComponentType()) {
						//TODO Handle component types properly if necessary
					} else if (!types[i].isCollectionType()) {
						//TODO take care of other types, composite primary keys etc
						log.info("Audit log module doesn't currently store changes in items of type:" + types[i]);
					}
					
					propertyChangesMap.put(propertyNames[i], new String[] { flattenedCurrentValue, flattenedPreviousValue });
				}
			}
			
			if (MapUtils.isNotEmpty(propertyChangesMap)) {
				if (log.isDebugEnabled())
					log.debug("Creating log entry for updated object with uuid:" + openmrsObject.getUuid() + " of type:"
					        + entity.getClass().getName());
				
				updates.get().add(openmrsObject);
				objectChangesMap.get().put(openmrsObject.getUuid(), propertyChangesMap);
			}
		}
		
		return false;
	}
	
	/**
	 * @see org.hibernate.EmptyInterceptor#onDelete(java.lang.Object, java.io.Serializable,
	 *      java.lang.Object[], java.lang.String[], org.hibernate.type.Type[])
	 */
	@Override
	public void onDelete(Object entity, Serializable id, Object[] state, String[] propertyNames, Type[] types) {
		if (isAuditable(entity)) {
			OpenmrsObject openmrsObject = (OpenmrsObject) entity;
			if (log.isDebugEnabled())
				log.debug("Creating log entry for deleted object with uuid:" + openmrsObject.getUuid() + " of type:"
				        + entity.getClass().getName());
			
			deletes.get().add(openmrsObject);
		}
	}
	
	/**
	 * @see org.hibernate.EmptyInterceptor#onCollectionUpdate(java.lang.Object,
	 *      java.io.Serializable)
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Override
	public void onCollectionUpdate(Object collection, Serializable key) throws CallbackException {
		if (collection != null && Collection.class.isAssignableFrom(collection.getClass())) {
			PersistentCollection persistentColl = ((PersistentCollection) collection);
			Object owningObject = persistentColl.getOwner();
			if (isAuditable(owningObject)) {
				Set<Object> removedItems = new HashSet<Object>();
				Collection currentColl = (Collection) collection;
				Map previousMap = (Map) persistentColl.getStoredSnapshot();
				removedItems.addAll(CollectionUtils.subtract(previousMap.values(), currentColl));
				
				String propertyName = persistentColl.getRole().substring(persistentColl.getRole().lastIndexOf('.') + 1);
				String ownerUuid = ((OpenmrsObject) owningObject).getUuid();
				if (objectChangesMap.get().get(ownerUuid) == null) {
					objectChangesMap.get().put(ownerUuid, new HashMap<String, String[]>());
				}
				objectChangesMap
				        .get()
				        .get(ownerUuid)
				        .put(propertyName,
				            new String[] { getItemUuidsOrIds(currentColl), getItemUuidsOrIds(previousMap.values()) });
				
				updates.get().add((OpenmrsObject) owningObject);
			}
		} else if (collection != null) {
			//TODO Handle persistent maps
			PersistentMap persistentMap = (PersistentMap) collection;
			Object owningObject = persistentMap.getOwner();
			if (getAuditLogDao().isMonitored(owningObject.getClass())) {
				log.error("PersistentMaps not supported: Can't create log entry for updated map:" + persistentMap.getRole()
				        + " in class:" + persistentMap.getOwner().getClass());
			}
		}
	}
	
	/**
	 * This is a hacky way to find all loaded classes in this session that have collections
	 * 
	 * @see org.hibernate.EmptyInterceptor#findDirty(java.lang.Object, java.io.Serializable,
	 *      java.lang.Object[], java.lang.Object[], java.lang.String[], org.hibernate.type.Type[])
	 */
	@Override
	public int[] findDirty(Object entity, Serializable id, Object[] currentState, Object[] previousState,
	                       String[] propertyNames, Type[] types) {
		if (getAuditLogDao().isMonitored(entity.getClass())) {
			if (entityCollectionsMap.get().get(entity) == null) {
				//This is the first time we are trying to find collection elements for this object
				if (log.isDebugEnabled())
					log.debug("Finding collections for object:" + entity.getClass() + " #" + id);
				
				for (int i = 0; i < propertyNames.length; i++) {
					if (types[i].isCollectionType()) {
						Object coll = currentState[i];
						if (coll != null && Collection.class.isAssignableFrom(coll.getClass())) {
							Collection<?> collection = (Collection<?>) coll;
							if (!collection.isEmpty()) {
								if (entityCollectionsMap.get().get(entity) == null) {
									entityCollectionsMap.get().put(entity, new ArrayList<Collection<?>>());
								}
								
								entityCollectionsMap.get().get(entity).add(collection);
							}
						} else {
							//TODO handle maps too because hibernate treats maps to be of CollectionType
						}
					}
				}
			}
		}
		
		return super.findDirty(entity, id, currentState, previousState, propertyNames, types);
	}
	
	/**
	 * @see org.hibernate.EmptyInterceptor#beforeTransactionCompletion(org.hibernate.Transaction)
	 */
	@Override
	public void beforeTransactionCompletion(Transaction tx) {
		//TODO This should typically happen in a separate thread for performance purposes
		try {
			if (inserts.get().isEmpty() && updates.get().isEmpty() && deletes.get().isEmpty())
				return;
			
			try {
				User user = Context.getAuthenticatedUser();
				Date tempDate = (date.get() != null) ? date.get() : new Date();
				//TODO handle daemon or un authenticated operations
				
				for (OpenmrsObject insert : inserts.get()) {
					AuditLog auditLog = new AuditLog(insert.getClass().getName(), insert.getUuid(), Action.CREATED, user,
					        tempDate);
					auditLog.setUuid(UUID.randomUUID().toString());
					getAuditLogDao().save(auditLog);
				}
				
				for (OpenmrsObject delete : deletes.get()) {
					AuditLog auditLog = new AuditLog(delete.getClass().getName(), delete.getUuid(), Action.DELETED, user,
					        tempDate);
					auditLog.setUuid(UUID.randomUUID().toString());
					getAuditLogDao().save(auditLog);
				}
				
				Map<String, List<AuditLog>> ownerUuidChildLogsMap = new HashMap<String, List<AuditLog>>();
				
				//Will use this to avoid creating logs for collections elements multiple times
				Map<String, AuditLog> childbjectUuidAuditLogMap = new HashMap<String, AuditLog>();
				//If we have any entities in the session that have child collections and there were some updates, 
				//check all collection items to find dirty ones so that we can mark the the owners as dirty too
				//I.e if a ConceptName/Mapping/Description was edited, mark the the Concept as dirty too
				
				for (Map.Entry<Object, List<Collection<?>>> entry : entityCollectionsMap.get().entrySet()) {
					for (Collection<?> coll : entry.getValue()) {
						for (Object obj : coll) {
							//If a collection item was updated and no other update had been made on the owner
							if (updates.get().contains(obj)) {
								OpenmrsObject owner = (OpenmrsObject) entry.getKey();
								if (updates.get().contains(owner)) {
									if (log.isDebugEnabled())
										log.debug("There is already an  auditlog for:" + owner.getClass() + " - "
										        + owner.getUuid());
								} else {
									if (log.isDebugEnabled())
										log.debug("Creating log entry for edited object with uuid:" + owner.getUuid()
										        + " of type:" + owner.getClass().getName()
										        + " due to an update for a item in a child collection");
									updates.get().add(owner);
								}
								
								if (getAuditLogDao().isMonitored(obj.getClass())) {
									if (ownerUuidChildLogsMap == null)
										ownerUuidChildLogsMap = new HashMap<String, List<AuditLog>>();
									if (ownerUuidChildLogsMap.get(owner.getUuid()) == null)
										ownerUuidChildLogsMap.put(owner.getUuid(), new ArrayList<AuditLog>());
									
									OpenmrsObject collElement = (OpenmrsObject) obj;
									AuditLog childLog = createAuditLog(collElement, Action.UPDATED, user, tempDate);
									ownerUuidChildLogsMap.get(owner.getUuid()).add(childLog);
									
									childbjectUuidAuditLogMap.put(collElement.getUuid(), childLog);
								}
								
								//TODO add this collection to the list of changes properties
								/*Map<String, String[]> propertyValuesMap = objectChangesMap.get().get(owner.getUuid());
								if(propertyValuesMap == null)
									propertyValuesMap = new HashMap<String, String[]>();
									propertyValuesMap.put(arg0, arg1);*/
							}
						}
					}
				}
				
				entityCollectionsMap.remove();
				
				for (OpenmrsObject update : updates.get()) {
					//If this is a collection element, we already created a log for it
					AuditLog auditLog = childbjectUuidAuditLogMap.get(update.getUuid());
					if (auditLog == null)
						auditLog = createAuditLog(update, Action.UPDATED, user, tempDate);
					
					if ((ownerUuidChildLogsMap != null && ownerUuidChildLogsMap.containsKey(update.getUuid()))) {
						for (AuditLog al : ownerUuidChildLogsMap.get(update.getUuid())) {
							auditLog.addChildAuditLog(al);
						}
						
					}
					getAuditLogDao().save(auditLog);
				}
			}
			catch (Exception e) {
				//error should not bubble out of the intercepter
				log.error("An error occured while creating audit log(s):", e);
			}
		}
		finally {
			//cleanup
			inserts.remove();
			updates.remove();
			deletes.remove();
			objectChangesMap.remove();
			entityCollectionsMap.remove();
			date.remove();
		}
	}
	
	private AuditLog createAuditLog(OpenmrsObject object, Action action, User user, Date tempDate) {
		AuditLog auditLog = new AuditLog(object.getClass().getName(), object.getUuid(), action, user, tempDate);
		if (action == Action.UPDATED) {
			Map<String, String[]> propertyValuesMap = objectChangesMap.get().get(object.getUuid());
			if (propertyValuesMap != null) {
				auditLog.setChangesData(AuditLogUtil.generateChangesData(propertyValuesMap));
			}
		}
		return auditLog;
	}
	
	/**
	 * Checks if a class is marked as monitored or is explicitly monitored
	 * 
	 * @param entity
	 * @return
	 */
	private boolean isAuditable(Object entity) {
		return getAuditLogDao().isMonitored(entity.getClass()) || getAuditLogDao().isImplicitlyMonitored(entity.getClass());
	}
	
	/**
	 * @param collection
	 * @return
	 */
	private String getItemUuidsOrIds(Collection<?> collection) {
		String currElementUuidsOrIds = "";
		boolean isFirst = true;
		Class<?> elementClass = null;
		for (Object currItem : collection) {
			if (currItem == null)
				continue;
			
			String uuidOrId = "";
			if (elementClass == null)
				elementClass = currItem.getClass();
			if (OpenmrsObject.class.isAssignableFrom(elementClass)) {
				try {
					uuidOrId += ((OpenmrsObject) currItem).getUuid();
				}
				catch (Exception e) {
					//ignore, some classes don't support getUuid
				}
			}
			if (StringUtils.isBlank(uuidOrId)) {
				ClassMetadata metadata = getAuditLogDao().getClassMetadata(elementClass);
				if (metadata != null) {
					if (metadata.getIdentifier(currItem, EntityMode.POJO) != null) {
						uuidOrId = metadata.getIdentifier(currItem, EntityMode.POJO).toString();
					}
					if (StringUtils.isNotBlank(uuidOrId))
						uuidOrId = AuditLogConstants.ID_LABEL + uuidOrId;
				} else {
					//This is none persistent type e.g Integer in case of cohort members
					if (Date.class.isAssignableFrom(elementClass)) {
						uuidOrId = new SimpleDateFormat(AuditLogConstants.DATE_FORMAT).format(currItem);
					} else if (Enum.class.isAssignableFrom(elementClass)) {
						uuidOrId = ((Enum<?>) currItem).name();
					} else if (Class.class.isAssignableFrom(elementClass)) {
						uuidOrId = ((Class<?>) currItem).getName();
					} else {
						uuidOrId = currItem.toString();
					}
				}
			} else {
				uuidOrId = AuditLogConstants.UUID_LABEL + uuidOrId;
			}
			if (StringUtils.isNotBlank(uuidOrId)) {
				if (isFirst) {
					currElementUuidsOrIds += uuidOrId;
					isFirst = false;
				} else {
					currElementUuidsOrIds += "," + uuidOrId;
				}
			}
		}
		if (StringUtils.isBlank(currElementUuidsOrIds))
			currElementUuidsOrIds = null;
		
		return currElementUuidsOrIds;
	}
}
