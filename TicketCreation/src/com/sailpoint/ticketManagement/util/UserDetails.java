package com.sailpoint.ticketManagement.util;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import sailpoint.api.IdentityService;
import sailpoint.api.SailPointContext;

import sailpoint.object.ApprovalItem;
import sailpoint.object.Attributes;
import sailpoint.object.Custom;
import sailpoint.object.EntitlementGroup;
import sailpoint.object.Filter;
import sailpoint.object.Identity;
import sailpoint.object.Link;
import sailpoint.object.QueryOptions;
import sailpoint.object.WorkItem;

import sailpoint.tools.GeneralException;
import sailpoint.tools.Util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class UserDetails {

    private static final Log log = LogFactory.getLog(UserDetails.class);

    private final SailPointContext context;

    public UserDetails(SailPointContext context) {
        this.context = context;
    }

    public Map<String, Object> getUserInformation(String userId, Custom customObject) throws GeneralException {
        Map<String, Object> response = new HashMap<String, Object>();

        if (Util.isEmpty(userId)) {
            log.warn("getUserInformation called with empty userId");
            response.put("message", "User ID is required.");
            return response;
        }

        if (customObject == null) {
            log.warn("Custom object is null while fetching user information for userId: " + userId);
            response.put("message", "Unable to retrieve user details. Configuration is missing.");
            return response;
        }

        Identity identity = context.getObjectByName(Identity.class, userId);
        if (identity == null) {
            log.warn("No identity found for userId: " + userId);
            response.put("message", "Unable to retrieve user details. Identity not found.");
            return response;
        }

        Object userInformationObject = customObject.get("userInformation");
        if (!(userInformationObject instanceof Map)) {
            log.warn("userInformation entry is missing or invalid in custom object for userId: " + userId);
            response.put("message", "Unable to retrieve user details. userInformation mapping is missing.");
            return response;
        }

        Map<String, String> userInformationMap = (Map<String, String>) userInformationObject;
        if (Util.isEmpty(userInformationMap)) {
            log.warn("userInformation mapping is empty in custom object for userId: " + userId);
            response.put("message", "Unable to retrieve user details. userInformation mapping is empty.");
            return response;
        }

        StringBuilder summaryBuilder = new StringBuilder();

        for (Map.Entry<String, String> entry : userInformationMap.entrySet()) {
            if (entry == null) {
                continue;
            }

            String label = entry.getKey();
            String fieldName = entry.getValue();

            if (Util.isEmpty(label) || Util.isEmpty(fieldName)) {
                log.debug("Skipping invalid userInformation entry for userId: " + userId);
                continue;
            }

            Object fieldValue = getIdentityFieldValue(identity, fieldName);
            String displayValue = fieldValue != null ? String.valueOf(fieldValue) : "Not Available";

            summaryBuilder.append(label)
                          .append(": ")
                          .append(displayValue)
                          .append("\n");
        }

        if (summaryBuilder.length() == 0) {
            log.warn("No usable fields found in userInformation mapping for userId: " + userId);
            response.put("message", "No user information available.");
            return response;
        }

        response.put("message", summaryBuilder.toString().trim());
        log.debug("Successfully built user information for userId: " + userId);
        return response;
    }

    public Map<String, Object> getUsersPendingRequests(String userId) throws GeneralException {
        Map<String, Object> response = new HashMap<String, Object>();

        if (Util.isEmpty(userId)) {
            log.warn("getUsersPendingRequests called with empty userId");
            response.put("message", "User ID is required.");
            return response;
        }

        QueryOptions queryOptions = new QueryOptions();
        queryOptions.addFilter(Filter.eq("type", "Approval"));
        queryOptions.addFilter(Filter.eq("targetName", userId));
        queryOptions.setCloneResults(true);

        List<String> pendingRequestLines = new ArrayList<String>();
        Iterator<?> workItemIterator = null;

        try {
            workItemIterator = context.search(WorkItem.class, queryOptions);

            int requestCount = 0;
            while (workItemIterator != null && workItemIterator.hasNext()) {
                WorkItem workItem = (WorkItem) workItemIterator.next();
                if (workItem == null || workItem.getApprovalSet() == null) {
                    continue;
                }

                List<ApprovalItem> approvalItems = workItem.getApprovalSet().getItems();
                if (Util.isEmpty(approvalItems)) {
                    continue;
                }

                for (ApprovalItem approvalItem : approvalItems) {
                    if (approvalItem == null) {
                        continue;
                    }

                    String applicationName = approvalItem.getApplicationName();
                    String displayName = approvalItem.getDisplayName();

                    if (!Util.isEmpty(applicationName) || !Util.isEmpty(displayName)) {
                        requestCount++;
                        pendingRequestLines.add(
                            requestCount + ". Application: " +
                            safeValue(applicationName) +
                            " Request: " +
                            safeValue(displayName)
                        );
                    }
                }
            }

            if (Util.isEmpty(pendingRequestLines)) {
                response.put("message", "No pending approvals found for user: " + userId + ".");
            } else {
                StringBuilder messageBuilder = new StringBuilder("Pending Approvals:\n");
                for (String line : pendingRequestLines) {
                    messageBuilder.append(line).append("\n");
                }
                response.put("message", messageBuilder.toString().trim());
            }

            log.debug("Pending request lookup completed for userId: " + userId);
            return response;

        } finally {
            if (workItemIterator != null) {
                Util.flushIterator(workItemIterator);
            }
        }
    }

    public Map<String, Object> getUserLinkInformation(String userId) throws GeneralException {
        Map<String, Object> response = new HashMap<String, Object>();
        List<String> activeLinks = new ArrayList<String>();
        List<String> inactiveLinks = new ArrayList<String>();

        if (Util.isEmpty(userId)) {
            log.warn("getUserLinkInformation called with empty userId");
            response.put("message", "User ID is required.");
            return response;
        }

        Identity identity = context.getObjectByName(Identity.class, userId);
        if (identity == null) {
            log.warn("No identity found while fetching link information for userId: " + userId);
            response.put("message", "Unable to find identity for user: " + userId);
            return response;
        }

        IdentityService identityService = new IdentityService(context);
        List<Link> links = identityService.getLinks(identity, 0, 0);

        for (Link link : Util.safeIterable(links)) {
            if (link == null) {
                continue;
            }

            String applicationName = safeValue(link.getApplicationName());
            String nativeIdentity = safeValue(link.getNativeIdentity());
            String linkEntry = applicationName + " - " + nativeIdentity;

            if (link.isDisabled()) {
                inactiveLinks.add(linkEntry);
            } else {
                activeLinks.add(linkEntry);
            }
        }

        StringBuilder messageBuilder = new StringBuilder();

        if (!Util.isEmpty(activeLinks)) {
            messageBuilder.append("Active Links:\n");
            for (String activeLink : activeLinks) {
                messageBuilder.append("- ").append(activeLink).append("\n");
            }
        }

        if (!Util.isEmpty(inactiveLinks)) {
            if (messageBuilder.length() > 0) {
                messageBuilder.append("\n");
            }

            messageBuilder.append("Inactive Links:\n");
            for (String inactiveLink : inactiveLinks) {
                messageBuilder.append("- ").append(inactiveLink).append("\n");
            }
        }

        if (messageBuilder.length() == 0) {
            String identityDisplayName = !Util.isEmpty(identity.getDisplayName()) ? identity.getDisplayName() : userId;
            response.put("message", "No links found for user: " + identityDisplayName);
        } else {
            response.put("message", messageBuilder.toString().trim());
        }

        log.debug("User link information lookup completed for userId: " + userId);
        return response;
    }

    public Map<String, Object> getUserEntitlementInformation(String userId) throws GeneralException {
        Map<String, Object> response = new HashMap<String, Object>();
        List<String> entitlementValues = new ArrayList<String>();

        if (Util.isEmpty(userId)) {
            log.warn("getUserEntitlementInformation called with empty userId");
            response.put("message", "User ID is required.");
            return response;
        }

        Identity identity = context.getObjectByName(Identity.class, userId);
        if (identity == null) {
            log.warn("No identity found while fetching entitlement information for userId: " + userId);
            response.put("message", "Unable to find identity for user: " + userId);
            return response;
        }

        List<EntitlementGroup> entitlementGroups = identity.getExceptions();
        if (!Util.isEmpty(entitlementGroups)) {
            for (EntitlementGroup entitlementGroup : entitlementGroups) {
                if (entitlementGroup == null) {
                    continue;
                }

                Attributes attributes = entitlementGroup.getAttributes();
                if (attributes == null) {
                    continue;
                }

                Object groupsObject = attributes.get("groups");
                if (groupsObject instanceof String) {
                    entitlementValues.add((String) groupsObject);
                } else if (groupsObject instanceof List) {
                    List<?> groupList = (List<?>) groupsObject;
                    for (Object groupValue : groupList) {
                        if (groupValue != null) {
                            entitlementValues.add(String.valueOf(groupValue));
                        }
                    }
                }
            }
        }

        if (!Util.isEmpty(entitlementValues)) {
            response.put("userEntitlementInformation", entitlementValues);
        } else {
            response.put("message", "No entitlements found for user: " + userId);
        }

        log.debug("User entitlement information lookup completed for userId: " + userId);
        return response;
    }

    private Object getIdentityFieldValue(Identity identity, String fieldName) {
        if (identity == null || Util.isEmpty(fieldName)) {
            return null;
        }

        if ("firstname".equalsIgnoreCase(fieldName)) {
            return identity.getFirstname();
        } else if ("lastname".equalsIgnoreCase(fieldName)) {
            return identity.getLastname();
        } else if ("displayName".equalsIgnoreCase(fieldName)) {
            return identity.getDisplayName();
        } else if ("email".equalsIgnoreCase(fieldName)) {
            return identity.getEmail();
        } else if ("manager".equalsIgnoreCase(fieldName)) {
            return identity.getManager() != null ? identity.getManager().getDisplayName() : null;
        }

        return identity.getAttribute(fieldName);
    }

    private String safeValue(Object value) {
        return value == null ? "Not Available" : String.valueOf(value);
    }
}