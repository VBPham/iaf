/*
 * $Log: MessagingSource.java,v $
 * Revision 1.1  2010-01-28 14:48:42  L190409
 * renamed 'Connection' classes to 'MessageSource'
 *
 * Revision 1.17  2009/09/07 13:18:24  Gerrit van Brakel <gerrit.van.brakel@ibissource.org>
 * moved return statement out of finally clause
 *
 * Revision 1.16  2009/06/29 12:12:09  Gerrit van Brakel <gerrit.van.brakel@ibissource.org>
 * removed modifications for connection pooling tracing
 *
 * Revision 1.15  2009/06/05 14:19:23  Gerrit van Brakel <gerrit.van.brakel@ibissource.org>
 * removed 'synchronized' from getConnection()
 *
 * Revision 1.14  2008/07/24 12:20:00  Gerrit van Brakel <gerrit.van.brakel@ibissource.org>
 * added support for authenticated JMS
 *
 * Revision 1.13  2008/01/03 15:49:35  Gerrit van Brakel <gerrit.van.brakel@ibissource.org>
 * made connectionFactory getter public
 *
 * Revision 1.12  2007/10/08 12:20:04  Gerrit van Brakel <gerrit.van.brakel@ibissource.org>
 * changed HashMap to Map where possible
 *
 * Revision 1.11  2007/02/12 13:58:11  Gerrit van Brakel <gerrit.van.brakel@ibissource.org>
 * Logger from LogUtil
 *
 * Revision 1.10  2006/03/15 14:12:44  Gerrit van Brakel <gerrit.van.brakel@ibissource.org>
 * removed logging in create/release Session/Connection, as this happens to often
 *
 * Revision 1.9  2006/02/28 08:44:16  Gerrit van Brakel <gerrit.van.brakel@ibissource.org>
 * cleanUp on close configurable
 *
 * Revision 1.8  2006/02/09 08:01:07  Gerrit van Brakel <gerrit.van.brakel@ibissource.org>
 * keep counts of open connections and sessions
 *
 * Revision 1.7  2006/01/02 12:04:22  Gerrit van Brakel <gerrit.van.brakel@ibissource.org>
 * improved logging
 *
 * Revision 1.6  2005/12/28 08:51:03  Gerrit van Brakel <gerrit.van.brakel@ibissource.org>
 * changed some IbisExceptions to JmsExceptions
 *
 * Revision 1.5  2005/12/20 16:58:32  Gerrit van Brakel <gerrit.van.brakel@ibissource.org>
 * implemented support for connection-pooling
 *
 * Revision 1.4  2005/11/02 09:40:52  Gerrit van Brakel <gerrit.van.brakel@ibissource.org>
 * made useSingleDynamicReplyQueue configurable from appConstants
 *
 * Revision 1.3  2005/10/26 08:18:59  Gerrit van Brakel <gerrit.van.brakel@ibissource.org>
 * pulled dynamic reply code out of IfsaConnection up to here
 *
 * Revision 1.2  2005/10/24 15:11:17  Gerrit van Brakel <gerrit.van.brakel@ibissource.org>
 * made sessionsArePooled configurable via appConstant 'jms.sessionsArePooled'
 *
 * Revision 1.1  2005/10/20 15:34:11  Gerrit van Brakel <gerrit.van.brakel@ibissource.org>
 * renamed JmsConnection into ConnectionBase
 *
 * Revision 1.3  2005/10/18 06:58:46  Gerrit van Brakel <gerrit.van.brakel@ibissource.org>
 * corrected version string
 *
 * Revision 1.2  2005/10/18 06:57:55  Gerrit van Brakel <gerrit.van.brakel@ibissource.org>
 * close returns true when underlying connection is acually closed
 *
 * Revision 1.1  2005/05/03 15:59:55  Gerrit van Brakel <gerrit.van.brakel@ibissource.org>
 * rework of shared connection code
 *
 */
package nl.nn.adapterframework.jms;

import java.lang.reflect.Field;
import java.util.Hashtable;
import java.util.Map;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.Queue;
import javax.jms.QueueConnection;
import javax.jms.QueueConnectionFactory;
import javax.jms.QueueSession;
import javax.jms.Session;
import javax.jms.TemporaryQueue;
import javax.jms.TopicConnection;
import javax.jms.TopicConnectionFactory;
import javax.naming.Context;

import nl.nn.adapterframework.core.IbisException;
import nl.nn.adapterframework.extensions.ifsa.IfsaException;
import nl.nn.adapterframework.util.AppConstants;
import nl.nn.adapterframework.util.ClassUtils;
import nl.nn.adapterframework.util.Counter;
import nl.nn.adapterframework.util.CredentialFactory;
import nl.nn.adapterframework.util.LogUtil;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.builder.ReflectionToStringBuilder;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.log4j.Logger;

/**
 * Generic Source for JMS connection, to be shared for JMS Objects that can use the same. 
 * 
 * @author  Gerrit van Brakel
 * @version Id
 */
public class MessagingSource  {
	protected Logger log = LogUtil.getLogger(this);

	private int referenceCount;
	private final static String CONNECTIONS_ARE_POOLED_KEY="jms.connectionsArePooled";
	private static Boolean connectionsArePooledStore=null; 
	private final static String SESSIONS_ARE_POOLED_KEY="jms.sessionsArePooled";
	private static Boolean sessionsArePooledStore=null; 
	private final static String USE_SINGLE_DYNAMIC_REPLY_QUEUE_KEY="jms.useSingleDynamicReplyQueue";
	private static Boolean useSingleDynamicReplyQueueStore=null; 
	private final static String CLEANUP_ON_CLOSE_KEY="jms.cleanUpOnClose";
	private static Boolean cleanUpOnClose=null; 

	private String authAlias;

	private Counter openConnectionCount = new Counter(0);
	private Counter openSessionCount = new Counter(0);
	
	private String id;
	
	private Context context = null;
	private ConnectionFactory connectionFactory = null;
	private Connection globalConnection=null; // only used when connections are not pooled
	
	private Map siblingMap;
	private Hashtable connectionTable; // hashtable is synchronized and does not permit nulls

	private Queue globalDynamicReplyQueue = null;
	
	protected MessagingSource(String id, Context context, ConnectionFactory connectionFactory, Map siblingMap, String authAlias) {
		super();
		referenceCount=0;
		this.id=id;
		this.context=context;
		this.connectionFactory=connectionFactory;
		this.siblingMap=siblingMap;
		siblingMap.put(id, this);
		if (connectionsArePooled()) {
			connectionTable = new Hashtable();
		}
		this.authAlias=authAlias;
		log.debug(getLogPrefix()+"set id ["+id+"] context ["+context+"] connectionFactory ["+connectionFactory+"] authAlias ["+authAlias+"]");
	}
		
	public synchronized boolean close() throws IbisException
	{
		if (--referenceCount<=0 && cleanUpOnClose()) {
			log.debug(getLogPrefix()+"reference count ["+referenceCount+"], cleaning up global objects");
			siblingMap.remove(getId());
			try {
				deleteDynamicQueue(globalDynamicReplyQueue);
				if (globalConnection != null) { 
					log.debug(getLogPrefix()+"closing global Connection");
					globalConnection.close();
					openConnectionCount.decrease();
				}
				if (openSessionCount.getValue()!=0) {
					log.warn(getLogPrefix()+"open session count after closing ["+openSessionCount.getValue()+"]");
				}
				if (openConnectionCount.getValue()!=0) {
					log.warn(getLogPrefix()+"open connection count after closing ["+openConnectionCount.getValue()+"]");
				}
				if (context != null) {
					context.close(); 
				}
			} catch (Exception e) {
				throw new IbisException("exception closing connection", e);
			} finally {
				globalDynamicReplyQueue=null;
				connectionFactory = null;
				globalConnection=null;
				context = null;
			}
			return true;
		} else {
			if (log.isDebugEnabled()) log.debug(getLogPrefix()+"reference count ["+referenceCount+"], no cleanup");
			return false;
		}
	}

	public synchronized void increaseReferences() {
		referenceCount++;
	}
	
	public synchronized void decreaseReferences() {
		referenceCount--;
	}

	public Context getContext() {
		return context;
	}

	public ConnectionFactory getConnectionFactory() {
		return connectionFactory;
	}


	public String getPhysicalName() {
		String result="";
		result+=ClassUtils.reflectionToString(connectionFactory, "factory");
		return result;
	}
	
	protected Connection createConnection() throws JMSException {
		if (StringUtils.isNotEmpty(authAlias)) {
			CredentialFactory cf = new CredentialFactory(authAlias,null,null);
			if (log.isDebugEnabled()) log.debug("using userId ["+cf.getUsername()+"] to create Connection");
			if (connectionFactory instanceof QueueConnectionFactory) {
				return ((QueueConnectionFactory)connectionFactory).createQueueConnection(cf.getUsername(),cf.getPassword());
			} else {
				return ((TopicConnectionFactory)connectionFactory).createTopicConnection(cf.getUsername(),cf.getPassword());
			}
		}
		if (connectionFactory instanceof QueueConnectionFactory) {
			return ((QueueConnectionFactory)connectionFactory).createQueueConnection();
		} else {
			return ((TopicConnectionFactory)connectionFactory).createTopicConnection();
		}
	}
	
	private Connection createAndStartConnection() throws JMSException {
		Connection connection;
		// do not log, as this may happen very often
//		if (log.isDebugEnabled()) log.debug(getLogPrefix()+"creating Connection, openConnectionCount before ["+openConnectionCount.getValue()+"]");
		connection = createConnection();
		openConnectionCount.increase();
		connection.start();
		return connection;
	}

	private Connection getConnection() throws JMSException {
		if (connectionsArePooled()) {
			return createAndStartConnection();
		} else {
			synchronized (this) {
				if (globalConnection == null) {
					globalConnection = createAndStartConnection();
				}
			}
			return globalConnection;
		}
	}

	private void releaseConnection(Connection connection) {
		if (connection != null && connectionsArePooled()) {
			try {
				// do not log, as this may happen very often
//				if (log.isDebugEnabled()) log.debug(getLogPrefix()+"closing Connection, openConnectionCount will become ["+(openConnectionCount.getValue()-1)+"]");
				connection.close();
				openConnectionCount.decrease();
			} catch (JMSException e) {
				log.error(getLogPrefix()+"Exception closing Connection", e);
			}
		}
	}

	public Session createSession(boolean transacted, int acknowledgeMode) throws IbisException {
		Connection connection=null;;
		Session session;
		try {
			connection = getConnection();
		} catch (JMSException e) {
			throw new JmsException("could not obtain Connection", e);
		}
		try {
			// do not log, as this may happen very often
//			if (log.isDebugEnabled()) log.debug(getLogPrefix()+"creating Session, openSessionCount before ["+openSessionCount.getValue()+"]");
			if (connection instanceof QueueConnection) {
				session = ((QueueConnection)connection).createQueueSession(transacted, acknowledgeMode);
			} else {
				session = ((TopicConnection)connection).createTopicSession(transacted, acknowledgeMode);
			}
			openSessionCount.increase();
			if (connectionsArePooled()) {
				connectionTable.put(session,connection);
			}
			return session;
		} catch (JMSException e) {
			releaseConnection(connection);
			throw new JmsException("could not create Session", e);
		}
	}
	
	public void releaseSession(Session session) { 
		if (session != null) {
			if (connectionsArePooled()) {
				Connection connection = (Connection)connectionTable.remove(session);
				try {
					// do not log, as this may happen very often
//					if (log.isDebugEnabled()) log.debug(getLogPrefix()+"closing Session, openSessionCount will become ["+(openSessionCount.getValue()-1)+"]");
					session.close();
					openSessionCount.decrease();
				} catch (JMSException e) {
					log.error(getLogPrefix()+"Exception closing Session", e);
				} finally {
					releaseConnection(connection);
				}
			} else {
				try {
					if (log.isDebugEnabled()) log.debug(getLogPrefix()+"closing Session");
					session.close();
				} catch (JMSException e) {
					log.error(getLogPrefix()+"Exception closing Session", e);
				}
			}
		}
	}

	protected synchronized boolean connectionsArePooled() {
		if (connectionsArePooledStore==null) {
			boolean pooled=AppConstants.getInstance().getBoolean(CONNECTIONS_ARE_POOLED_KEY, false);
			connectionsArePooledStore = new Boolean(pooled);
		}
		return connectionsArePooledStore.booleanValue();
	}

	public synchronized boolean sessionsArePooled() {
		if (sessionsArePooledStore==null) {
			boolean pooled=AppConstants.getInstance().getBoolean(SESSIONS_ARE_POOLED_KEY, false);
			sessionsArePooledStore = new Boolean(pooled);
		}
		return sessionsArePooledStore.booleanValue();
	}

	protected synchronized boolean useSingleDynamicReplyQueue() {
		if (connectionsArePooled()) {
			return false; // dynamic reply queues are connection-based.
		}
		if (useSingleDynamicReplyQueueStore==null) {
			boolean useSingleQueue=AppConstants.getInstance().getBoolean(USE_SINGLE_DYNAMIC_REPLY_QUEUE_KEY, true);
			useSingleDynamicReplyQueueStore = new Boolean(useSingleQueue);
		}
		return useSingleDynamicReplyQueueStore.booleanValue();
	}


	public synchronized boolean cleanUpOnClose() {
		if (cleanUpOnClose==null) {
			boolean cleanup=AppConstants.getInstance().getBoolean(CLEANUP_ON_CLOSE_KEY, true);
			cleanUpOnClose = new Boolean(cleanup);
		}
		return cleanUpOnClose.booleanValue();
	}


	private void deleteDynamicQueue(Queue queue) throws IfsaException {
		if (queue!=null) {
			try {
				if (!(queue instanceof TemporaryQueue)) {
					throw new IfsaException("Queue ["+queue.getQueueName()+"] is not a TemporaryQueue");
				}
				TemporaryQueue tqueue = (TemporaryQueue)queue;
				tqueue.delete();
			} catch (JMSException e) {
				throw new IfsaException("cannot delete temporary queue",e);
			}
		}
	}

	public Queue getDynamicReplyQueue(QueueSession session) throws JMSException {
		Queue result;
		if (useSingleDynamicReplyQueue()) {
			synchronized (this) {
				if (globalDynamicReplyQueue==null) {
					globalDynamicReplyQueue=session.createTemporaryQueue();
					log.info(getLogPrefix()+"created dynamic replyQueue ["+globalDynamicReplyQueue.getQueueName()+"]");
				}
			}
			result = globalDynamicReplyQueue;
		} else {
			result = session.createTemporaryQueue();
		}
		return result;
	}
	
	public void releaseDynamicReplyQueue(Queue replyQueue) throws IfsaException {
		if (!useSingleDynamicReplyQueue()) {
			deleteDynamicQueue(replyQueue);
		}
	}


	protected String getLogPrefix() {
		return "["+getId()+"] "; 
	}

	public String getId() {
		return id;
	}


	public void setAuthAlias(String string) {
		authAlias = string;
	}
	public String getAuthAlias() {
		return authAlias;
	}

}