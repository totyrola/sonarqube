/*
 * SonarQube
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
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
package org.sonar.db.permission;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.sonar.api.security.DefaultGroups;
import org.sonar.db.Dao;
import org.sonar.db.DbSession;

import static org.sonar.db.DatabaseUtils.executeLargeInputs;
import static org.sonar.db.DatabaseUtils.executeLargeInputsWithoutOutput;

public class GroupPermissionDao implements Dao {

  private static final String COMPONENT_ID_PARAMETER = "componentId";
  private static final String ANYONE_GROUP_PARAMETER = "anyoneGroup";

  /**
   * @deprecated not compatible with organizations.
   */
  @Deprecated
  public int countGroups(DbSession session, String permission, @Nullable Long componentId) {
    Map<String, Object> parameters = new HashMap<>();
    parameters.put("permission", permission);
    parameters.put(ANYONE_GROUP_PARAMETER, DefaultGroups.ANYONE);
    parameters.put(COMPONENT_ID_PARAMETER, componentId);

    return mapper(session).countGroups(parameters);
  }

  /**
   * @return group names, sorted in alphabetical order
   * @deprecated not compatible with organizations.
   */
  @Deprecated
  public List<String> selectGroupNamesByPermissionQuery(DbSession dbSession, PermissionQuery query) {
    return mapper(dbSession).selectGroupNamesByPermissionQuery(query, new RowBounds(query.getPageOffset(), query.getPageSize()));
  }

  /**
   * @deprecated not compatible with organizations.
   */
  @Deprecated
  public int countGroupsByPermissionQuery(DbSession dbSession, PermissionQuery query) {
    return mapper(dbSession).countGroupsByPermissionQuery(query);
  }

  /**
   * @deprecated group name parameter is not enough to identify a group. It is not compatible with organizations.
   */
  @Deprecated
  public List<GroupPermissionDto> selectGroupPermissionsByGroupNamesAndProject(DbSession dbSession, List<String> groupNames, @Nullable Long projectId) {
    return executeLargeInputs(groupNames, groups -> mapper(dbSession).selectGroupPermissionByGroupNames(groups, projectId));
  }

  /**
   * Each row returns a {@link CountPerProjectPermission}
   */
  public void groupsCountByComponentIdAndPermission(DbSession dbSession, List<Long> componentIds, ResultHandler resultHandler) {
    Map<String, Object> parameters = new HashMap<>(2);
    parameters.put(ANYONE_GROUP_PARAMETER, DefaultGroups.ANYONE);

    executeLargeInputsWithoutOutput(
      componentIds,
      partitionedComponentIds -> {
        parameters.put("componentIds", partitionedComponentIds);
        mapper(dbSession).groupsCountByProjectIdAndPermission(parameters, resultHandler);
        return null;
      });
  }

  /**
   * @return the permissions granted to the requested group, optionally on the requested project. An
   * empty list is returned if the group or project do not exist.
   */
  public List<String> selectGroupPermissions(DbSession session, long groupId, @Nullable Long projectId) {
    return session.getMapper(GroupPermissionMapper.class).selectGroupPermissions(groupId, projectId);
  }

  /**
   * @return the permissions granted to Anyone virtual group, optionally on the requested project. An
   * empty list is returned if the project does not exist.
   * @deprecated not compatible with organizations if {@code projectId} is null. Should have an organization parameter.
   */
  @Deprecated
  public List<String> selectAnyonePermissions(DbSession session, @Nullable Long projectId) {
    return session.getMapper(GroupPermissionMapper.class).selectAnyonePermissions(projectId);
  }

  public void insert(DbSession dbSession, GroupPermissionDto dto) {
    mapper(dbSession).insert(dto);
  }

  private static GroupPermissionMapper mapper(DbSession session) {
    return session.getMapper(GroupPermissionMapper.class);
  }

}
