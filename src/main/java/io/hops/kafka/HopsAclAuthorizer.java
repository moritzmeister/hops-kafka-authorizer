package io.hops.kafka;

import io.hops.kafka.authorizer.tables.HopsAcl;
import kafka.network.RequestChannel;
import kafka.security.auth.Acl;
import kafka.security.auth.Authorizer;
import kafka.security.auth.Operation;
import kafka.security.auth.Resource;
import org.apache.kafka.common.security.auth.KafkaPrincipal;

import java.sql.SQLException;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;

/**
 *
 * Authorizer class for HopsWorks Kafka. Authorizer project users by extracting their project specific name from
 * the SSL/TLS certificate CN field.
 * <p>
 */
public class HopsAclAuthorizer implements Authorizer {
  
  private static final Logger LOG = Logger.getLogger("kafka.authorizer.logger");
  //List of users that will be treated as super users and will have access to
  //all the resources for all actions from all osts, defaults to no super users.
  private Set<KafkaPrincipal> superUsers = new HashSet<>();
  
  DbConnection dbConnection;
  //<TopicName,<Principal,HopsAcl>>
  final Map<String, Map<String, List<HopsAcl>>> acls;

  private final String sqlExceptionPattern = "HikariDataSource.+has been closed.";
  private final Pattern r = Pattern.compile(sqlExceptionPattern);

  public HopsAclAuthorizer() {
    acls = new HashMap<>();
  }

  public HopsAclAuthorizer(Map<String, Map<String, List<HopsAcl>>> acls) {
    this.acls = acls;
  }

  /**
   * Guaranteed to be called before any authorize call is made.
   *
   * @param configs
   */
  @Override
  public void configure(java.util.Map<String, ?> configs) {
    Object obj = configs.get(Consts.SUPERUSERS_PROP);
    
    if (obj != null) {
      String superUsersStr = (String) obj;
      String[] superUserStrings = superUsersStr.split(Consts.SEMI_COLON);
      
      for (String user : superUserStrings) {
        superUsers.add(KafkaPrincipal.fromString(user.trim()));
      }
    } else {
      superUsers = new HashSet<>();
    }
    
    try {
      //initialize database connection.
      dbConnection = new DbConnection(
          configs.get(Consts.DATABASE_URL).toString(),
          configs.get(Consts.DATABASE_USERNAME).toString(),
          configs.get(Consts.DATABASE_PASSWORD).toString(),
          Integer.parseInt(configs.get(Consts.DATABASE_MAX_POOL_SIZE).toString()),
          configs.get(Consts.DATABASE_CACHE_PREPSTMTS).toString(),
          configs.get(Consts.DATABASE_PREPSTMT_CACHE_SIZE).toString(),
          configs.get(Consts.DATABASE_PREPSTMT_CACHE_SQL_LIMIT).toString());
    } catch (SQLException ex) {
      LOG.error("HopsAclAuthorizer could not connect to database at:" + configs.get(Consts.DATABASE_URL).toString(),
          ex);
    }
    
    //Start the ACLs update thread
    ExecutorService executor = Executors.newSingleThreadExecutor();
    executor.submit((Runnable) () -> {
        while (true) {
          try {
            updateAclCache();
            Thread.sleep(Long.parseLong(String.valueOf(configs.get(Consts.DATABASE_ACL_POLLING_FREQUENCY_MS))));
          } catch (InterruptedException ex) {
            LOG.error("HopsAclAuthorizer db polling exception", ex);
            acls.clear();
          }
        }
      });
  }
  
  @Override
  public boolean authorize(RequestChannel.Session session, Operation operation, Resource resource) {
    KafkaPrincipal principal = session.principal();
    String host = session.clientAddress().getHostAddress();
    String topicName = resource.name();
    String projectName__userName = principal.getName();
  
    LOG.debug("authorize :: session:" + session);
    LOG.debug("authorize :: principal.name:" + principal.getName());
    LOG.debug("authorize :: principal.type:" + principal.getPrincipalType());
    LOG.debug("authorize :: operation:" + operation);
    LOG.debug("authorize :: host:" + host);
    LOG.debug("authorize :: resource:" + resource);
    LOG.debug("authorize :: topicName:" + topicName);
    LOG.debug("authorize :: projectName__userName:" + projectName__userName);
    
    if (projectName__userName.equalsIgnoreCase(Consts.ANONYMOUS)) {
      LOG.info("No Acl found for cluster authorization, user:" + projectName__userName);
      return false;
    }
    
    if (isSuperUser(principal)) {
      return true;
    }
    
    if (resource.resourceType().equals(
        kafka.security.auth.ResourceType$.MODULE$.fromString(Consts.CLUSTER))) {
      LOG.info("This is cluster authorization for broker: " + projectName__userName);
      return false;
    }
    if (resource.resourceType().equals(
        kafka.security.auth.ResourceType$.MODULE$.fromString(Consts.GROUP))) {
      //Check if group requested starts with projectname__ and is equal to the current users project
      String projectCN = projectName__userName.split(Consts.PROJECT_USER_DELIMITER)[0];
      if (resource.name().contains(Consts.PROJECT_USER_DELIMITER)) {
        String projectConsumerGroup = resource.name().split(Consts.PROJECT_USER_DELIMITER)[0];
        LOG.debug("Consumer group :: projectCN:" + projectCN);
        LOG.debug("Consumer group :: projectConsumerGroup:" + projectConsumerGroup);
        //Chec
        if (!projectCN.equals(projectConsumerGroup)) {
          LOG.info("Principal:" + projectName__userName + " is not allowed to access group:" + resource.name());
          return false;
        }
      }
      LOG.info("Principal:" + projectName__userName + " is allowed to access group:" + resource.name());
      return true;
    }
  
    // First check if there are ACLs for the topic available, if not -> refresh cache
    Map<String, List<HopsAcl>> topicAcls;
    synchronized (acls) {
      topicAcls = acls.get(topicName);
    }

    if (topicAcls == null) {
      updateAclCache();
      synchronized (acls) {
        topicAcls = acls.get(topicName);
      }

      // if the topic Acls is still null, then throw an exception
      if (topicAcls == null) {
        LOG.info("For principal: " + projectName__userName
            + ", topic:" + topicName
            + ", operation:" + operation
            + ", resource:" + resource
            + ", Topic not found");
        return false;
      }
    }

    return authorizeProjectUser(operation, resource, host, topicAcls, projectName__userName);
  }

  private boolean authorizeProjectUser(Operation operation, Resource resource, String host,
                                       Map<String, List<HopsAcl>> topicAcls, String projectName__userName) {

    List<HopsAcl> projectUserAcls = topicAcls.get(projectName__userName);
    if (projectUserAcls == null || projectUserAcls.isEmpty()) {
      LOG.info("For principal: " + projectName__userName
          + ", operation:" + operation
          + ", resource:" + resource
          + ", allowMatch: false - no ACL found");
      return false;
    }

    //check if there is any Deny acl match that would disallow this operation.
    boolean denyMatch = aclMatch(operation.name(), projectName__userName,
      host, Consts.DENY, projectUserAcls.get(0).getProjectRole(), projectUserAcls);

    LOG.info("For principal: " + projectName__userName + ", operation:" + operation + ", resource:" + resource
        + ", denyMatch:" + denyMatch);

    boolean allowMatch = aclMatch(operation.name(), projectName__userName,
        host, Consts.ALLOW, projectUserAcls.get(0).getProjectRole(), projectUserAcls);

    LOG.info("For principal: " + projectName__userName + ", operation:" + operation + ", resource:" + resource
      + ", allowMatch:" + allowMatch);

    return !denyMatch && allowMatch;
  }
  
  private Boolean aclMatch(String operations, String principal,
                           String host, String permissionType, String role,
                           List<HopsAcl> acls) {
    LOG.debug("aclMatch :: Operation:" + operations);
    LOG.debug("aclMatch :: principal:" + principal);
    LOG.debug("aclMatch :: host:" + host);
    LOG.debug("aclMatch :: permissionType:" + permissionType);
    LOG.debug("aclMatch :: role:" + role);
    LOG.debug("aclMatch :: acls:" + acls);

    for (HopsAcl acl : acls) {
      LOG.debug("aclMatch.acl" + acl);
      if (acl.getPermissionType().equalsIgnoreCase(permissionType)
          && (acl.getPrincipal().equalsIgnoreCase(principal) || acl.getPrincipal().equals(Consts.WILDCARD))
          && (acl.getOperationType().equalsIgnoreCase(operations) || acl.getOperationType().equalsIgnoreCase(
          Consts.WILDCARD))
          && (acl.getHost().equalsIgnoreCase(host) || acl.getHost().equals(Consts.WILDCARD))
          && (acl.getRole().equalsIgnoreCase(role) || acl.getRole().equals(Consts.WILDCARD))) {
        return true;
      }
    }
    return false;
  }

  private void updateAclCache() {
    try {
      Map<String, Map<String, List<HopsAcl>>> dbAcls = dbConnection.getAcls();
      LOG.debug("Acls:" + dbAcls);
      synchronized (acls) {
        acls.clear();
        acls.putAll(dbAcls);
      }
    } catch (SQLException ex) {
      // If the exception is due to a closed pool, it is most likely that the service has been externally
      // shut down and there is not much we can do here.
      Matcher m = r.matcher(ex.getMessage());
      if (!m.find()) {
        LOG.error("HopsAclAuthorizer could not query database", ex);
      }
      //Clear the acls to indicate the error getting the acls from the database
      acls.clear();
    }
  }
  
  private boolean isSuperUser(KafkaPrincipal principal) {
    if (superUsers.contains(principal)) {
      LOG.debug("principal = " + principal + " is a super user, allowing operation without checking acls.");
      return true;
    }
    LOG.debug("principal = " + principal + " is not a super user.");
    return false;
  }

  @Override
  public void addAcls(scala.collection.immutable.Set<Acl> acls, Resource resource) {
  }

  @Override
  public boolean removeAcls(scala.collection.immutable.Set<Acl> acls, Resource resource) {
    return false;
  }

  @Override
  public boolean removeAcls(Resource resource) {
    return false;
  }

  @Override
  public scala.collection.immutable.Set<Acl> getAcls(Resource resource) {
    return null;
  }

  @Override
  public scala.collection.immutable.Map<Resource,
      scala.collection.immutable.Set<Acl>> getAcls(KafkaPrincipal principal) {
    return null;
  }

  @Override
  public scala.collection.immutable.Map<Resource, scala.collection.immutable.Set<Acl>> getAcls() {
    return null;
  }

  @Override
  public void close() {
    dbConnection.close();
  }

  // For testing
  public void setSuperUsers(Set<KafkaPrincipal> superUsers) {
    this.superUsers = superUsers;
  }

  // For testing
  public void setDbConnection(DbConnection dbConnection) {
    this.dbConnection = dbConnection;
  }
}
