/*
 * SonarQube
 * Copyright (C) 2009-2019 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.ce.task.projectanalysis.step;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.CheckForNull;
import org.sonar.api.issue.Issue;
import org.sonar.api.notifications.Notification;
import org.sonar.api.rules.RuleType;
import org.sonar.api.utils.Duration;
import org.sonar.ce.task.projectanalysis.analysis.AnalysisMetadataHolder;
import org.sonar.ce.task.projectanalysis.analysis.Branch;
import org.sonar.ce.task.projectanalysis.component.Component;
import org.sonar.ce.task.projectanalysis.component.CrawlerDepthLimit;
import org.sonar.ce.task.projectanalysis.component.DepthTraversalTypeAwareCrawler;
import org.sonar.ce.task.projectanalysis.component.TreeRootHolder;
import org.sonar.ce.task.projectanalysis.component.TypeAwareVisitorAdapter;
import org.sonar.ce.task.projectanalysis.issue.IssueCache;
import org.sonar.ce.task.projectanalysis.issue.RuleRepository;
import org.sonar.ce.task.projectanalysis.notification.NewIssuesNotificationFactory;
import org.sonar.ce.task.step.ComputationStep;
import org.sonar.core.issue.DefaultIssue;
import org.sonar.core.util.CloseableIterator;
import org.sonar.core.util.stream.MoreCollectors;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.component.BranchType;
import org.sonar.db.user.UserDto;
import org.sonar.server.issue.notification.IssueChangeNotification;
import org.sonar.server.issue.notification.MyNewIssuesNotification;
import org.sonar.server.issue.notification.NewIssuesNotification;
import org.sonar.server.issue.notification.NewIssuesStatistics;
import org.sonar.server.notification.NotificationService;

import static java.util.Collections.singleton;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.StreamSupport.stream;
import static org.sonar.ce.task.projectanalysis.component.ComponentVisitor.Order.POST_ORDER;
import static org.sonar.db.component.BranchType.PULL_REQUEST;
import static org.sonar.db.component.BranchType.SHORT;

/**
 * Reads issues from disk cache and send related notifications. For performance reasons,
 * the standard notification DB queue is not used as a temporary storage. Notifications
 * are directly processed by {@link NotificationService}.
 */
public class SendIssueNotificationsStep implements ComputationStep {
  /**
   * Types of the notifications sent by this step
   */
  static final Set<Class<? extends Notification>> NOTIF_TYPES = ImmutableSet.of(NewIssuesNotification.class, MyNewIssuesNotification.class, IssueChangeNotification.class);

  private final IssueCache issueCache;
  private final RuleRepository rules;
  private final TreeRootHolder treeRootHolder;
  private final NotificationService service;
  private final AnalysisMetadataHolder analysisMetadataHolder;
  private final NewIssuesNotificationFactory newIssuesNotificationFactory;
  private final DbClient dbClient;

  private Map<String, Component> componentsByDbKey;

  public SendIssueNotificationsStep(IssueCache issueCache, RuleRepository rules, TreeRootHolder treeRootHolder,
    NotificationService service, AnalysisMetadataHolder analysisMetadataHolder,
    NewIssuesNotificationFactory newIssuesNotificationFactory, DbClient dbClient) {
    this.issueCache = issueCache;
    this.rules = rules;
    this.treeRootHolder = treeRootHolder;
    this.service = service;
    this.analysisMetadataHolder = analysisMetadataHolder;
    this.newIssuesNotificationFactory = newIssuesNotificationFactory;
    this.dbClient = dbClient;
  }

  @Override
  public void execute(ComputationStep.Context context) {
    BranchType branchType = analysisMetadataHolder.getBranch().getType();
    if (branchType == PULL_REQUEST || branchType == SHORT) {
      return;
    }

    Component project = treeRootHolder.getRoot();
    NotificationStatistics notificationStatistics = new NotificationStatistics();
    if (service.hasProjectSubscribersForTypes(analysisMetadataHolder.getProject().getUuid(), NOTIF_TYPES)) {
      doExecute(notificationStatistics, project);
    }
    notificationStatistics.dumpTo(context);
  }

  private void doExecute(NotificationStatistics notificationStatistics, Component project) {
    long analysisDate = analysisMetadataHolder.getAnalysisDate();
    Predicate<DefaultIssue> isOnLeakPredicate = i -> i.isNew() && i.creationDate().getTime() >= truncateToSeconds(analysisDate);
    NewIssuesStatistics newIssuesStats = new NewIssuesStatistics(isOnLeakPredicate);
    Map<String, UserDto> assigneesByUuid;
    try (DbSession dbSession = dbClient.openSession(false)) {
      Iterable<DefaultIssue> iterable = issueCache::traverse;
      Set<String> assigneeUuids = stream(iterable.spliterator(), false).map(DefaultIssue::assignee).filter(Objects::nonNull).collect(toSet());
      assigneesByUuid = dbClient.userDao().selectByUuids(dbSession, assigneeUuids).stream().collect(toMap(UserDto::getUuid, dto -> dto));
    }

    try (CloseableIterator<DefaultIssue> issues = issueCache.traverse()) {
      processIssues(newIssuesStats, issues, project, assigneesByUuid, notificationStatistics);
    }
    if (newIssuesStats.hasIssuesOnLeak()) {
      sendNewIssuesNotification(newIssuesStats, project, assigneesByUuid, analysisDate, notificationStatistics);
      sendMyNewIssuesNotification(newIssuesStats, project, assigneesByUuid, analysisDate, notificationStatistics);
    }
  }

  /**
   * Truncated the analysis date to seconds before comparing it to {@link Issue#creationDate()} is required because
   * {@link DefaultIssue#setCreationDate(Date)} does it.
   */
  private static long truncateToSeconds(long analysisDate) {
    Instant instant = new Date(analysisDate).toInstant();
    instant = instant.truncatedTo(ChronoUnit.SECONDS);
    return Date.from(instant).getTime();
  }

  private void processIssues(NewIssuesStatistics newIssuesStats, CloseableIterator<DefaultIssue> issues, Component project, Map<String, UserDto> usersDtoByUuids,
    NotificationStatistics notificationStatistics) {
    int batchSize = 1000;
    List<DefaultIssue> loadedIssues = new ArrayList<>(batchSize);
    while (issues.hasNext()) {
      DefaultIssue issue = issues.next();
      if (issue.type() != RuleType.SECURITY_HOTSPOT) {
        if (issue.isNew() && issue.resolution() == null) {
          newIssuesStats.add(issue);
        } else if (issue.isChanged() && issue.mustSendNotifications()) {
          loadedIssues.add(issue);
        }
      }

      if (loadedIssues.size() >= batchSize) {
        sendIssueChangeNotification(loadedIssues, project, usersDtoByUuids, notificationStatistics);
        loadedIssues.clear();
      }
    }

    if (!loadedIssues.isEmpty()) {
      sendIssueChangeNotification(loadedIssues, project, usersDtoByUuids, notificationStatistics);
    }
  }

  private void sendIssueChangeNotification(Collection<DefaultIssue> issues, Component project, Map<String, UserDto> usersDtoByUuids,
    NotificationStatistics notificationStatistics) {
    Set<IssueChangeNotification> notifications = issues.stream()
      .map(issue -> {
        IssueChangeNotification notification = new IssueChangeNotification();
        notification.setRuleName(rules.getByKey(issue.ruleKey()).getName());
        notification.setIssue(issue);
        notification.setAssignee(usersDtoByUuids.get(issue.assignee()));
        notification.setProject(project.getKey(), project.getName(), getBranchName(), getPullRequest());
        getComponentKey(issue).ifPresent(c -> notification.setComponent(c.getKey(), c.getName()));
        return notification;
      })
      .collect(MoreCollectors.toSet(issues.size()));

    notificationStatistics.issueChangesDeliveries += service.deliverEmails(notifications);
    notificationStatistics.issueChanges++;

    // compatibility with old API
    notifications.forEach(notification -> notificationStatistics.issueChangesDeliveries += service.deliver(notification));
  }

  private void sendNewIssuesNotification(NewIssuesStatistics statistics, Component project, Map<String, UserDto> assigneesByUuid,
    long analysisDate, NotificationStatistics notificationStatistics) {
    NewIssuesStatistics.Stats globalStatistics = statistics.globalStatistics();
    NewIssuesNotification notification = newIssuesNotificationFactory
      .newNewIssuesNotification(assigneesByUuid)
      .setProject(project.getKey(), project.getName(), getBranchName(), getPullRequest())
      .setProjectVersion(project.getProjectAttributes().getProjectVersion())
      .setAnalysisDate(new Date(analysisDate))
      .setStatistics(project.getName(), globalStatistics)
      .setDebt(Duration.create(globalStatistics.effort().getOnLeak()));
    notificationStatistics.newIssuesDeliveries += service.deliverEmails(singleton(notification));
    notificationStatistics.newIssues++;

    // compatibility with old API
    notificationStatistics.newIssuesDeliveries += service.deliver(notification);
  }

  private void sendMyNewIssuesNotification(NewIssuesStatistics statistics, Component project, Map<String, UserDto> assigneesByUuid, long analysisDate,
    NotificationStatistics notificationStatistics) {
    Map<String, UserDto> userDtoByUuid = loadUserDtoByUuid(statistics);
    Set<MyNewIssuesNotification> myNewIssuesNotifications = statistics.getAssigneesStatistics().entrySet()
      .stream()
      .filter(e -> e.getValue().hasIssuesOnLeak())
      .map(e -> {
        String assigneeUuid = e.getKey();
        NewIssuesStatistics.Stats assigneeStatistics = e.getValue();
        MyNewIssuesNotification myNewIssuesNotification = newIssuesNotificationFactory
          .newMyNewIssuesNotification(assigneesByUuid)
          .setAssignee(userDtoByUuid.get(assigneeUuid));
        myNewIssuesNotification
          .setProject(project.getKey(), project.getName(), getBranchName(), getPullRequest())
          .setProjectVersion(project.getProjectAttributes().getProjectVersion())
          .setAnalysisDate(new Date(analysisDate))
          .setStatistics(project.getName(), assigneeStatistics)
          .setDebt(Duration.create(assigneeStatistics.effort().getOnLeak()));

        return myNewIssuesNotification;
      })
      .collect(MoreCollectors.toSet(statistics.getAssigneesStatistics().size()));

    notificationStatistics.myNewIssuesDeliveries += service.deliverEmails(myNewIssuesNotifications);
    notificationStatistics.myNewIssues += myNewIssuesNotifications.size();

    // compatibility with old API
    myNewIssuesNotifications
      .forEach(e -> notificationStatistics.myNewIssuesDeliveries += service.deliver(e));
  }

  private Map<String, UserDto> loadUserDtoByUuid(NewIssuesStatistics statistics) {
    List<Map.Entry<String, NewIssuesStatistics.Stats>> entriesWithIssuesOnLeak = statistics.getAssigneesStatistics().entrySet()
      .stream().filter(e -> e.getValue().hasIssuesOnLeak()).collect(toList());
    List<String> assigneeUuids = entriesWithIssuesOnLeak.stream().map(Map.Entry::getKey).collect(toList());
    try (DbSession dbSession = dbClient.openSession(false)) {
      return dbClient.userDao().selectByUuids(dbSession, assigneeUuids).stream().collect(toMap(UserDto::getUuid, u -> u));
    }
  }

  private Optional<Component> getComponentKey(DefaultIssue issue) {
    if (componentsByDbKey == null) {
      final ImmutableMap.Builder<String, Component> builder = ImmutableMap.builder();
      new DepthTraversalTypeAwareCrawler(
        new TypeAwareVisitorAdapter(CrawlerDepthLimit.LEAVES, POST_ORDER) {
          @Override
          public void visitAny(Component component) {
            builder.put(component.getDbKey(), component);
          }
        }).visit(this.treeRootHolder.getRoot());
      this.componentsByDbKey = builder.build();
    }
    return Optional.ofNullable(componentsByDbKey.get(issue.componentKey()));
  }

  @Override
  public String getDescription() {
    return "Send issue notifications";
  }

  @CheckForNull
  private String getBranchName() {
    Branch branch = analysisMetadataHolder.getBranch();
    return branch.isMain() || branch.getType() == PULL_REQUEST ? null : branch.getName();
  }

  @CheckForNull
  private String getPullRequest() {
    Branch branch = analysisMetadataHolder.getBranch();
    return branch.getType() == PULL_REQUEST ? analysisMetadataHolder.getPullRequestKey() : null;
  }

  private static class NotificationStatistics {
    private int issueChanges = 0;
    private int issueChangesDeliveries = 0;
    private int newIssues = 0;
    private int newIssuesDeliveries = 0;
    private int myNewIssues = 0;
    private int myNewIssuesDeliveries = 0;

    private void dumpTo(ComputationStep.Context context) {
      context.getStatistics()
        .add("newIssuesNotifs", newIssues)
        .add("newIssuesDeliveries", newIssuesDeliveries)
        .add("myNewIssuesNotifs", myNewIssues)
        .add("myNewIssuesDeliveries", myNewIssuesDeliveries)
        .add("changesNotifs", issueChanges)
        .add("changesDeliveries", issueChangesDeliveries);
    }
  }
}
