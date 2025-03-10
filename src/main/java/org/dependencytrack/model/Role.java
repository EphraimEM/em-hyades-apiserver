/*
 * This file is part of Dependency-Track.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) OWASP Foundation. All Rights Reserved.
 */
package org.dependencytrack.model;

import alpine.common.validation.RegexSequence;
import alpine.model.Permission;
import alpine.server.json.TrimmedStringDeserializer;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import javax.jdo.annotations.Column;
import javax.jdo.annotations.Element;
import javax.jdo.annotations.Extension;
import javax.jdo.annotations.FetchGroup;
import javax.jdo.annotations.IdGeneratorStrategy;
import javax.jdo.annotations.Join;
import javax.jdo.annotations.Order;
import javax.jdo.annotations.PersistenceCapable;
import javax.jdo.annotations.Persistent;
import javax.jdo.annotations.PrimaryKey;
import javax.jdo.annotations.Unique;

/**
 * Model for tracking roles. Roles define static sets of permissions
 * that can be applied to a user with the scope of a project.
 *
 * @author Allen Shearin
 * @since 5.6.0
 */
@PersistenceCapable
@FetchGroup(name = "ALL", members = {
        @Persistent(name = "name"),
        @Persistent(name = "permissions"),
        @Persistent(name = "uuid"),
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Role implements Serializable {

    private static final long serialVersionUID = -7592438796591673355L;

    /**
     * Defines JDO fetch groups for this class.
     */
    public enum FetchGroup {
        ALL
    }

    @PrimaryKey
    @Persistent(valueStrategy = IdGeneratorStrategy.NATIVE)
    @JsonIgnore
    private long id;

    @Persistent
    @Unique(name = "ROLE_NAME_IDX", deferred = "true")
    @Column(name = "NAME", jdbcType = "VARCHAR", allowsNull = "false")
    @NotBlank
    @Size(min = 1, max = 255)
    @JsonDeserialize(using = TrimmedStringDeserializer.class)
    @Pattern(regexp = RegexSequence.Definition.PRINTABLE_CHARS,
            message = "The name may only contain printable characters")
    private String name;

    @Persistent(table = "ROLES_PERMISSIONS", defaultFetchGroup = "true")
    @Unique(name = "ROLES_PERMISSIONS_IDX")
    @Join(column = "ROLE_ID")
    @Element(column = "PERMISSION_ID")
    @Order(extensions = @Extension(vendorName = "datanucleus", key = "list-ordering", value = "name ASC"))
    private List<Permission> permissions;

    @Persistent(customValueStrategy = "uuid")
    @Unique(name = "ROLE_UUID_IDX")
    @Column(name = "UUID", sqlType = "UUID", allowsNull = "false")
    @NotNull
    private UUID uuid;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<Permission> getPermissions() {
        return permissions;
    }

    public void setPermissions(List<Permission> permissions) {
        this.permissions = permissions;
    }

    public void addPermissions(Permission... permissions) {
        if (this.permissions == null) {
            this.permissions = new ArrayList<>(Arrays.asList(permissions));

            return;
        }

        for (var permission : permissions)
            if (!this.permissions.contains(permission))
                this.permissions.add(permission);
    }

    public UUID getUuid() {
        return uuid;
    }

    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }

    @Override
    public String toString() {
        var permissionStrings = permissions.stream()
                .map(permission -> permission.getName())
                .toList();

        return "%s{id=%d, name='%s', description='%s', permissions=%s}".formatted(
                getClass().getSimpleName(),
                id,
                name,
                permissionStrings);
    }
}
