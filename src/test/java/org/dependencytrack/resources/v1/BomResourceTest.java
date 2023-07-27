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
 * Copyright (c) Steve Springett. All Rights Reserved.
 */
package org.dependencytrack.resources.v1;

import alpine.common.util.UuidUtil;
import alpine.server.filters.ApiFilter;
import alpine.server.filters.AuthenticationFilter;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.dependencytrack.ResourceTest;
import org.dependencytrack.auth.Permissions;
import org.dependencytrack.model.AnalysisResponse;
import org.dependencytrack.model.AnalysisState;
import org.dependencytrack.model.AnalyzerIdentity;
import org.dependencytrack.model.Classifier;
import org.dependencytrack.model.Component;
import org.dependencytrack.model.Project;
import org.dependencytrack.model.Severity;
import org.dependencytrack.model.Vulnerability;
import org.dependencytrack.model.WorkflowState;
import org.dependencytrack.model.WorkflowStep;
import org.dependencytrack.resources.v1.vo.BomSubmitRequest;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.glassfish.jersey.test.DeploymentContext;
import org.glassfish.jersey.test.ServletDeploymentContext;
import org.junit.Assert;
import org.junit.Test;

import javax.json.JsonObject;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.File;
import java.util.Base64;
import java.util.UUID;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.dependencytrack.model.WorkflowStatus.COMPLETED;
import static org.dependencytrack.model.WorkflowStatus.FAILED;
import static org.dependencytrack.model.WorkflowStatus.PENDING;
import static org.dependencytrack.model.WorkflowStep.BOM_CONSUMPTION;
import static org.dependencytrack.model.WorkflowStep.BOM_PROCESSING;
import static org.hamcrest.CoreMatchers.equalTo;

public class BomResourceTest extends ResourceTest {

    @Override
    protected DeploymentContext configureDeployment() {
        return ServletDeploymentContext.forServlet(new ServletContainer(
                new ResourceConfig(BomResource.class)
                        .register(ApiFilter.class)
                        .register(AuthenticationFilter.class)
                        .register(MultiPartFeature.class)))
                .build();
    }

    @Test
    public void exportProjectAsCycloneDxTest() {
        Project project = qm.createProject("Acme Example", null, "1.0", null, null, null, true, false);
        Component c = new Component();
        c.setProject(project);
        c.setName("sample-component");
        c.setVersion("1.0");
        Component component = qm.createComponent(c, false);
        Response response = target(V1_BOM + "/cyclonedx/project/" + project.getUuid()).request()
                .header(X_API_KEY, apiKey)
                .get(Response.class);
        Assert.assertEquals(200, response.getStatus(), 0);
        Assert.assertNull(response.getHeaderString(TOTAL_COUNT_HEADER));
        String body = getPlainTextBody(response);
        Assert.assertTrue(body.startsWith("{"));
    }

    @Test
    public void exportProjectAsCycloneDxInvalidTest() {
        Response response = target(V1_BOM + "/cyclonedx/project/" + UUID.randomUUID()).request()
                .header(X_API_KEY, apiKey)
                .get(Response.class);
        Assert.assertEquals(404, response.getStatus(), 0);
        Assert.assertNull(response.getHeaderString(TOTAL_COUNT_HEADER));
        String body = getPlainTextBody(response);
        Assert.assertEquals("The project could not be found.", body);
    }

    @Test
    public void exportProjectAsCycloneDxInventoryTest() {
        var vulnerability = new Vulnerability();
        vulnerability.setVulnId("INT-001");
        vulnerability.setSource(Vulnerability.Source.INTERNAL);
        vulnerability.setSeverity(Severity.HIGH);
        vulnerability = qm.createVulnerability(vulnerability, false);

        var project = new Project();
        project.setName("acme-app");
        project.setClassifier(Classifier.APPLICATION);
        project = qm.createProject(project, null, false);

        var componentWithoutVuln = new Component();
        componentWithoutVuln.setProject(project);
        componentWithoutVuln.setName("acme-lib-a");
        componentWithoutVuln.setVersion("1.0.0");
        componentWithoutVuln.setDirectDependencies("[]");
        componentWithoutVuln = qm.createComponent(componentWithoutVuln, false);

        var componentWithVuln = new Component();
        componentWithVuln.setProject(project);
        componentWithVuln.setName("acme-lib-b");
        componentWithVuln.setVersion("1.0.0");
        componentWithVuln.setDirectDependencies("[]");
        componentWithVuln = qm.createComponent(componentWithVuln, false);
        qm.addVulnerability(vulnerability, componentWithVuln, AnalyzerIdentity.INTERNAL_ANALYZER);

        var componentWithVulnAndAnalysis = new Component();
        componentWithVulnAndAnalysis.setProject(project);
        componentWithVulnAndAnalysis.setName("acme-lib-c");
        componentWithVulnAndAnalysis.setVersion("1.0.0");
        componentWithVulnAndAnalysis.setDirectDependencies("[]");
        componentWithVulnAndAnalysis = qm.createComponent(componentWithVulnAndAnalysis, false);
        qm.addVulnerability(vulnerability, componentWithVulnAndAnalysis, AnalyzerIdentity.INTERNAL_ANALYZER);
        qm.makeAnalysis(componentWithVulnAndAnalysis, vulnerability, AnalysisState.RESOLVED, null, AnalysisResponse.UPDATE, null, true);

        // Make componentWithoutVuln (acme-lib-a) depend on componentWithVuln (acme-lib-b)
        componentWithoutVuln.setDirectDependencies("""
                [
                    {"uuid": "%s"}
                ]
                """.formatted(componentWithVuln.getUuid()));

        // Make project depend on componentWithoutVuln (acme-lib-a)
        // and componentWithVulnAndAnalysis (acme-lib-c)
        project.setDirectDependencies("""
                [
                    {"uuid": "%s"},
                    {"uuid": "%s"}
                ]
                """
                .formatted(
                        componentWithoutVuln.getUuid(),
                        componentWithVulnAndAnalysis.getUuid()
                ));
        qm.persist(project);

        final Response response = target(V1_BOM + "/cyclonedx/project/" + project.getUuid())
                .queryParam("variant", "inventory")
                .request()
                .header(X_API_KEY, apiKey)
                .get(Response.class);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);

        final String jsonResponse = getPlainTextBody(response);
        assertThatJson(jsonResponse)
                .withMatcher("projectUuid", equalTo(project.getUuid().toString()))
                .withMatcher("componentWithoutVulnUuid", equalTo(componentWithoutVuln.getUuid().toString()))
                .withMatcher("componentWithVulnUuid", equalTo(componentWithVuln.getUuid().toString()))
                .withMatcher("componentWithVulnAndAnalysisUuid", equalTo(componentWithVulnAndAnalysis.getUuid().toString()))
                .isEqualTo(json("""
                        {
                            "bomFormat": "CycloneDX",
                            "specVersion": "1.4",
                            "serialNumber": "${json-unit.ignore}",
                            "version": 1,
                            "metadata": {
                                "timestamp": "${json-unit.any-string}",
                                "component": {
                                    "type": "application",
                                    "bom-ref": "${json-unit.matches:projectUuid}",
                                    "name": "acme-app",
                                    "version": "SNAPSHOT"
                                },
                                "tools": [
                                    {
                                        "vendor": "OWASP",
                                        "name": "Dependency-Track",
                                        "version": "${json-unit.any-string}"
                                    }
                                ]
                            },
                            "components": [
                                {
                                    "type": "library",
                                    "bom-ref": "${json-unit.matches:componentWithoutVulnUuid}",
                                    "name": "acme-lib-a",
                                    "version": "1.0.0"
                                },
                                {
                                    "type": "library",
                                    "bom-ref": "${json-unit.matches:componentWithVulnUuid}",
                                    "name": "acme-lib-b",
                                    "version": "1.0.0"
                                },
                                {
                                    "type": "library",
                                    "bom-ref": "${json-unit.matches:componentWithVulnAndAnalysisUuid}",
                                    "name": "acme-lib-c",
                                    "version": "1.0.0"
                                }
                            ],
                            "dependencies": [
                                {
                                    "ref": "${json-unit.matches:projectUuid}",
                                    "dependsOn": [
                                        "${json-unit.matches:componentWithoutVulnUuid}",
                                        "${json-unit.matches:componentWithVulnAndAnalysisUuid}"
                                    ]
                                },
                                {
                                    "ref": "${json-unit.matches:componentWithoutVulnUuid}",
                                    "dependsOn": [
                                        "${json-unit.matches:componentWithVulnUuid}"
                                    ]
                                },
                                {
                                    "ref": "${json-unit.matches:componentWithVulnUuid}",
                                    "dependsOn": []
                                },
                                {
                                    "ref": "${json-unit.matches:componentWithVulnAndAnalysisUuid}",
                                    "dependsOn": []
                                }
                            ]
                        }
                        """));

        // Ensure the dependency graph did not get deleted during export.
        // https://github.com/DependencyTrack/dependency-track/issues/2494
        qm.getPersistenceManager().refreshAll(project, componentWithoutVuln, componentWithVuln, componentWithVulnAndAnalysis);
        assertThat(project.getDirectDependencies()).isNotNull();
        assertThat(componentWithoutVuln.getDirectDependencies()).isNotNull();
        assertThat(componentWithVuln.getDirectDependencies()).isNotNull();
        assertThat(componentWithVulnAndAnalysis.getDirectDependencies()).isNotNull();
    }

    @Test
    public void exportProjectAsCycloneDxInventoryWithVulnerabilitiesTest() {
        var vulnerability = new Vulnerability();
        vulnerability.setVulnId("INT-001");
        vulnerability.setSource(Vulnerability.Source.INTERNAL);
        vulnerability.setSeverity(Severity.HIGH);
        vulnerability = qm.createVulnerability(vulnerability, false);

        var project = new Project();
        project.setName("acme-app");
        project.setClassifier(Classifier.APPLICATION);
        project = qm.createProject(project, null, false);

        var componentWithoutVuln = new Component();
        componentWithoutVuln.setProject(project);
        componentWithoutVuln.setName("acme-lib-a");
        componentWithoutVuln.setVersion("1.0.0");
        componentWithoutVuln.setDirectDependencies("[]");
        componentWithoutVuln = qm.createComponent(componentWithoutVuln, false);

        var componentWithVuln = new Component();
        componentWithVuln.setProject(project);
        componentWithVuln.setName("acme-lib-b");
        componentWithVuln.setVersion("1.0.0");
        componentWithVuln.setDirectDependencies("[]");
        componentWithVuln = qm.createComponent(componentWithVuln, false);
        qm.addVulnerability(vulnerability, componentWithVuln, AnalyzerIdentity.INTERNAL_ANALYZER);

        var componentWithVulnAndAnalysis = new Component();
        componentWithVulnAndAnalysis.setProject(project);
        componentWithVulnAndAnalysis.setName("acme-lib-c");
        componentWithVulnAndAnalysis.setVersion("1.0.0");
        componentWithVulnAndAnalysis.setDirectDependencies("[]");
        componentWithVulnAndAnalysis = qm.createComponent(componentWithVulnAndAnalysis, false);
        qm.addVulnerability(vulnerability, componentWithVulnAndAnalysis, AnalyzerIdentity.INTERNAL_ANALYZER);
        qm.makeAnalysis(componentWithVulnAndAnalysis, vulnerability, AnalysisState.RESOLVED, null, AnalysisResponse.UPDATE, null, true);

        // Make componentWithoutVuln (acme-lib-a) depend on componentWithVuln (acme-lib-b)
        componentWithoutVuln.setDirectDependencies("""
                [
                    {"uuid": "%s"}
                ]
                """.formatted(componentWithVuln.getUuid()));

        // Make project depend on componentWithoutVuln (acme-lib-a)
        // and componentWithVulnAndAnalysis (acme-lib-c)
        project.setDirectDependencies("""
                [
                    {"uuid": "%s"},
                    {"uuid": "%s"}
                ]
                """
                .formatted(
                        componentWithoutVuln.getUuid(),
                        componentWithVulnAndAnalysis.getUuid()
                ));
        qm.persist(project);

        final Response response = target(V1_BOM + "/cyclonedx/project/" + project.getUuid())
                .queryParam("variant", "withVulnerabilities")
                .request()
                .header(X_API_KEY, apiKey)
                .get(Response.class);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);

        final String jsonResponse = getPlainTextBody(response);
        assertThatJson(jsonResponse)
                .withMatcher("vulnUuid", equalTo(vulnerability.getUuid().toString()))
                .withMatcher("projectUuid", equalTo(project.getUuid().toString()))
                .withMatcher("componentWithoutVulnUuid", equalTo(componentWithoutVuln.getUuid().toString()))
                .withMatcher("componentWithVulnUuid", equalTo(componentWithVuln.getUuid().toString()))
                .withMatcher("componentWithVulnAndAnalysisUuid", equalTo(componentWithVulnAndAnalysis.getUuid().toString()))
                .isEqualTo(json("""
                {
                    "bomFormat": "CycloneDX",
                    "specVersion": "1.4",
                    "serialNumber": "${json-unit.ignore}",
                    "version": 1,
                    "metadata": {
                        "timestamp": "${json-unit.any-string}",
                        "component": {
                            "type": "application",
                            "bom-ref": "${json-unit.matches:projectUuid}",
                            "name": "acme-app",
                            "version": "SNAPSHOT"
                        },
                        "tools": [
                            {
                                "vendor": "OWASP",
                                "name": "Dependency-Track",
                                "version": "${json-unit.any-string}"
                            }
                        ]
                    },
                    "components": [
                        {
                            "type": "library",
                            "bom-ref": "${json-unit.matches:componentWithoutVulnUuid}",
                            "name": "acme-lib-a",
                            "version": "1.0.0"
                        },
                        {
                            "type": "library",
                            "bom-ref": "${json-unit.matches:componentWithVulnUuid}",
                            "name": "acme-lib-b",
                            "version": "1.0.0"
                        },
                        {
                            "type": "library",
                            "bom-ref": "${json-unit.matches:componentWithVulnAndAnalysisUuid}",
                            "name": "acme-lib-c",
                            "version": "1.0.0"
                        }
                    ],
                    "dependencies": [
                        {
                            "ref": "${json-unit.matches:projectUuid}",
                            "dependsOn": [
                                "${json-unit.matches:componentWithoutVulnUuid}",
                                "${json-unit.matches:componentWithVulnAndAnalysisUuid}"
                            ]
                        },
                        {
                            "ref": "${json-unit.matches:componentWithoutVulnUuid}",
                            "dependsOn": [
                                "${json-unit.matches:componentWithVulnUuid}"
                            ]
                        },
                        {
                            "ref": "${json-unit.matches:componentWithVulnUuid}",
                            "dependsOn": []
                        },
                        {
                            "ref": "${json-unit.matches:componentWithVulnAndAnalysisUuid}",
                            "dependsOn": []
                        }
                    ],
                    "vulnerabilities": [
                        {
                            "bom-ref": "${json-unit.matches:vulnUuid}",
                            "id": "INT-001",
                            "source": {
                                "name": "INTERNAL"
                            },
                            "ratings": [
                                {
                                    "source": {
                                        "name": "INTERNAL"
                                    },
                                    "severity": "high",
                                    "method": "other"
                                }
                            ],
                            "affects": [
                                {
                                    "ref": "${json-unit.matches:componentWithVulnUuid}"
                                }
                            ]
                        },
                        {
                            "bom-ref": "${json-unit.matches:vulnUuid}",
                            "id": "INT-001",
                            "source": {
                                "name": "INTERNAL"
                            },
                            "ratings": [
                                {
                                    "source": {
                                        "name": "INTERNAL"
                                    },
                                    "severity": "high",
                                    "method": "other"
                                }
                            ],
                            "affects": [
                                {
                                    "ref": "${json-unit.matches:componentWithVulnAndAnalysisUuid}"
                                }
                            ]
                        }
                    ]
                }
                """));

        // Ensure the dependency graph did not get deleted during export.
        // https://github.com/DependencyTrack/dependency-track/issues/2494
        qm.getPersistenceManager().refreshAll(project, componentWithoutVuln, componentWithVuln, componentWithVulnAndAnalysis);
        assertThat(project.getDirectDependencies()).isNotNull();
        assertThat(componentWithoutVuln.getDirectDependencies()).isNotNull();
        assertThat(componentWithVuln.getDirectDependencies()).isNotNull();
        assertThat(componentWithVulnAndAnalysis.getDirectDependencies()).isNotNull();
    }

    @Test
    public void exportProjectAsCycloneDxVdrTest() {
        var vulnerability = new Vulnerability();
        vulnerability.setVulnId("INT-001");
        vulnerability.setSource(Vulnerability.Source.INTERNAL);
        vulnerability.setSeverity(Severity.HIGH);
        vulnerability = qm.createVulnerability(vulnerability, false);

        var project = new Project();
        project.setName("acme-app");
        project.setClassifier(Classifier.APPLICATION);
        project = qm.createProject(project, null, false);

        var componentWithoutVuln = new Component();
        componentWithoutVuln.setProject(project);
        componentWithoutVuln.setName("acme-lib-a");
        componentWithoutVuln.setVersion("1.0.0");
        componentWithoutVuln.setDirectDependencies("[]");
        componentWithoutVuln = qm.createComponent(componentWithoutVuln, false);

        var componentWithVuln = new Component();
        componentWithVuln.setProject(project);
        componentWithVuln.setName("acme-lib-b");
        componentWithVuln.setVersion("1.0.0");
        componentWithVuln.setDirectDependencies("[]");
        componentWithVuln = qm.createComponent(componentWithVuln, false);
        qm.addVulnerability(vulnerability, componentWithVuln, AnalyzerIdentity.INTERNAL_ANALYZER);

        var componentWithVulnAndAnalysis = new Component();
        componentWithVulnAndAnalysis.setProject(project);
        componentWithVulnAndAnalysis.setName("acme-lib-c");
        componentWithVulnAndAnalysis.setVersion("1.0.0");
        componentWithVulnAndAnalysis.setDirectDependencies("[]");
        componentWithVulnAndAnalysis = qm.createComponent(componentWithVulnAndAnalysis, false);
        qm.addVulnerability(vulnerability, componentWithVulnAndAnalysis, AnalyzerIdentity.INTERNAL_ANALYZER);
        qm.makeAnalysis(componentWithVulnAndAnalysis, vulnerability, AnalysisState.RESOLVED, null, AnalysisResponse.UPDATE, null, true);

        // Make componentWithoutVuln (acme-lib-a) depend on componentWithVuln (acme-lib-b)
        componentWithoutVuln.setDirectDependencies("""
                [
                    {"uuid": "%s"}
                ]
                """.formatted(componentWithVuln.getUuid()));

        // Make project depend on componentWithoutVuln (acme-lib-a)
        // and componentWithVulnAndAnalysis (acme-lib-c)
        project.setDirectDependencies("""
                [
                    {"uuid": "%s"},
                    {"uuid": "%s"}
                ]
                """
                .formatted(
                        componentWithoutVuln.getUuid(),
                        componentWithVulnAndAnalysis.getUuid()
                ));
        qm.persist(project);

        final Response response = target(V1_BOM + "/cyclonedx/project/" + project.getUuid())
                .queryParam("variant", "vdr")
                .request()
                .header(X_API_KEY, apiKey)
                .get(Response.class);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);

        final String jsonResponse = getPlainTextBody(response);
        assertThatJson(jsonResponse)
                .withMatcher("vulnUuid", equalTo(vulnerability.getUuid().toString()))
                .withMatcher("projectUuid", equalTo(project.getUuid().toString()))
                .withMatcher("componentWithoutVulnUuid", equalTo(componentWithoutVuln.getUuid().toString()))
                .withMatcher("componentWithVulnUuid", equalTo(componentWithVuln.getUuid().toString()))
                .withMatcher("componentWithVulnAndAnalysisUuid", equalTo(componentWithVulnAndAnalysis.getUuid().toString()))
                .isEqualTo(json("""
                {
                    "bomFormat": "CycloneDX",
                    "specVersion": "1.4",
                    "serialNumber": "${json-unit.ignore}",
                    "version": 1,
                    "metadata": {
                        "timestamp": "${json-unit.any-string}",
                        "component": {
                            "type": "application",
                            "bom-ref": "${json-unit.matches:projectUuid}",
                            "name": "acme-app",
                            "version": "SNAPSHOT"
                        },
                        "tools": [
                            {
                                "vendor": "OWASP",
                                "name": "Dependency-Track",
                                "version": "${json-unit.any-string}"
                            }
                        ]
                    },
                    "components": [
                        {
                            "type": "library",
                            "bom-ref": "${json-unit.matches:componentWithoutVulnUuid}",
                            "name": "acme-lib-a",
                            "version": "1.0.0"
                        },
                        {
                            "type": "library",
                            "bom-ref": "${json-unit.matches:componentWithVulnUuid}",
                            "name": "acme-lib-b",
                            "version": "1.0.0"
                        },
                        {
                            "type": "library",
                            "bom-ref": "${json-unit.matches:componentWithVulnAndAnalysisUuid}",
                            "name": "acme-lib-c",
                            "version": "1.0.0"
                        }
                    ],
                    "dependencies": [
                        {
                            "ref": "${json-unit.matches:projectUuid}",
                            "dependsOn": [
                                "${json-unit.matches:componentWithoutVulnUuid}",
                                "${json-unit.matches:componentWithVulnAndAnalysisUuid}"
                            ]
                        },
                        {
                            "ref": "${json-unit.matches:componentWithoutVulnUuid}",
                            "dependsOn": [
                                "${json-unit.matches:componentWithVulnUuid}"
                            ]
                        },
                        {
                            "ref": "${json-unit.matches:componentWithVulnUuid}",
                            "dependsOn": []
                        },
                        {
                            "ref": "${json-unit.matches:componentWithVulnAndAnalysisUuid}",
                            "dependsOn": []
                        }
                    ],
                    "vulnerabilities": [
                        {
                            "bom-ref": "${json-unit.matches:vulnUuid}",
                            "id": "INT-001",
                            "source": {
                                "name": "INTERNAL"
                            },
                            "ratings": [
                                {
                                    "source": {
                                        "name": "INTERNAL"
                                    },
                                    "severity": "high",
                                    "method": "other"
                                }
                            ],
                            "affects": [
                                {
                                    "ref": "${json-unit.matches:componentWithVulnUuid}"
                                }
                            ]
                        },
                        {
                            "bom-ref": "${json-unit.matches:vulnUuid}",
                            "id": "INT-001",
                            "source": {
                                "name": "INTERNAL"
                            },
                            "ratings": [
                                {
                                    "source": {
                                        "name": "INTERNAL"
                                    },
                                    "severity": "high",
                                    "method": "other"
                                }
                            ],
                            "affects": [
                                {
                                    "ref": "${json-unit.matches:componentWithVulnAndAnalysisUuid}"
                                }
                            ],
                            "analysis": {
                                "state": "resolved",
                                "response": [
                                    "update"
                                ]
                            }
                        }
                    ]
                }
                """));

        // Ensure the dependency graph did not get deleted during export.
        // https://github.com/DependencyTrack/dependency-track/issues/2494
        qm.getPersistenceManager().refreshAll(project, componentWithoutVuln, componentWithVuln, componentWithVulnAndAnalysis);
        assertThat(project.getDirectDependencies()).isNotNull();
        assertThat(componentWithoutVuln.getDirectDependencies()).isNotNull();
        assertThat(componentWithVuln.getDirectDependencies()).isNotNull();
        assertThat(componentWithVulnAndAnalysis.getDirectDependencies()).isNotNull();
    }

    @Test
    public void exportComponentAsCycloneDx() {
        Project project = qm.createProject("Acme Example", null, null, null, null, null, false, false);
        Component c = new Component();
        c.setProject(project);
        c.setName("sample-component");
        c.setVersion("1.0");
        Component component = qm.createComponent(c, false);
        Response response = target(V1_BOM + "/cyclonedx/component/" + component.getUuid()).request()
                .header(X_API_KEY, apiKey)
                .get(Response.class);
        Assert.assertEquals(200, response.getStatus(), 0);
        Assert.assertNull(response.getHeaderString(TOTAL_COUNT_HEADER));
        String body = getPlainTextBody(response);
        Assert.assertTrue(body.startsWith("{"));
    }

    @Test
    public void exportComponentAsCycloneDxInvalid() {
        Response response = target(V1_BOM + "/cyclonedx/component/" + UUID.randomUUID()).request()
                .header(X_API_KEY, apiKey)
                .get(Response.class);
        Assert.assertEquals(404, response.getStatus(), 0);
        Assert.assertNull(response.getHeaderString(TOTAL_COUNT_HEADER));
        String body = getPlainTextBody(response);
        Assert.assertEquals("The component could not be found.", body);
    }

    @Test
    public void uploadBomTest() throws Exception {
        initializeWithPermissions(Permissions.BOM_UPLOAD);
        Project project = qm.createProject("Acme Example", null, "1.0", null, null, null, true, false);
        File file = new File(IOUtils.resourceToURL("/unit/bom-1.xml").toURI());
        String bomString = Base64.getEncoder().encodeToString(FileUtils.readFileToByteArray(file));
        BomSubmitRequest request = new BomSubmitRequest(project.getUuid().toString(), null, null, false, bomString);
        Response response = target(V1_BOM).request()
                .header(X_API_KEY, apiKey)
                .put(Entity.entity(request, MediaType.APPLICATION_JSON));
        Assert.assertEquals(200, response.getStatus(), 0);
        JsonObject json = parseJsonObject(response);
        Assert.assertNotNull(json);
        Assert.assertNotNull(json.getString("token"));
        Assert.assertTrue(UuidUtil.isValidUUID(json.getString("token")));
        UUID uuid = UUID.fromString(json.getString("token"));
        assertThat(qm.getAllWorkflowStatesForAToken(uuid)).satisfiesExactlyInAnyOrder(
               workflowState -> {
                   assertThat(workflowState.getStep()).isEqualTo(WorkflowStep.BOM_CONSUMPTION);
                   assertThat(workflowState.getToken()).isEqualTo(uuid);
                   assertThat(workflowState.getParent()).isNull();
                   assertThat(workflowState.getStartedAt()).isNull();
                   assertThat(workflowState.getUpdatedAt()).isNotNull();
               },
                workflowState -> {
                    assertThat(workflowState.getStep()).isEqualTo(WorkflowStep.BOM_PROCESSING);
                    assertThat(workflowState.getToken()).isEqualTo(uuid);
                    assertThat(workflowState.getParent()).isNotNull();
                    assertThat(workflowState.getStartedAt()).isNull();
                    assertThat(workflowState.getUpdatedAt()).isNotNull();
                },
                workflowState -> {
                    assertThat(workflowState.getStep()).isEqualTo(WorkflowStep.VULN_ANALYSIS);
                    assertThat(workflowState.getToken()).isEqualTo(uuid);
                    assertThat(workflowState.getParent()).isNotNull();
                    assertThat(workflowState.getStartedAt()).isNull();
                    assertThat(workflowState.getUpdatedAt()).isNotNull();
                },
                workflowState -> {
                    assertThat(workflowState.getStep()).isEqualTo(WorkflowStep.POLICY_EVALUATION);
                    assertThat(workflowState.getToken()).isEqualTo(uuid);
                    assertThat(workflowState.getParent()).isNotNull();
                    assertThat(workflowState.getStartedAt()).isNull();
                    assertThat(workflowState.getUpdatedAt()).isNotNull();
                },
                workflowState -> {
                    assertThat(workflowState.getStep()).isEqualTo(WorkflowStep.METRICS_UPDATE);
                    assertThat(workflowState.getToken()).isEqualTo(uuid);
                    assertThat(workflowState.getParent()).isNotNull();
                    assertThat(workflowState.getStartedAt()).isNull();
                    assertThat(workflowState.getUpdatedAt()).isNotNull();
                }
        );
    }

    @Test
    public void uploadNonCycloneDxBomTest() {
        initializeWithPermissions(Permissions.BOM_UPLOAD);
        Project project = qm.createProject("Acme Example", null, "1.0", null, null, null, true, false);
        String bomString = Base64.getEncoder().encodeToString("""
                SPDXVersion: SPDX-2.2
                DataLicense: CC0-1.0
                """.getBytes());
        BomSubmitRequest request = new BomSubmitRequest(project.getUuid().toString(), null, null, false, bomString);
        Response response = target(V1_BOM).request()
                .header(X_API_KEY, apiKey)
                .put(Entity.entity(request, MediaType.APPLICATION_JSON));
        Assert.assertEquals(400, response.getStatus(), 0);
        Assert.assertEquals("The uploaded file is not a CycloneDX BOM", getPlainTextBody(response));
    }

    @Test
    public void uploadInvalidCycloneDxBomTest() {
        initializeWithPermissions(Permissions.BOM_UPLOAD);
        Project project = qm.createProject("Acme Example", null, "1.0", null, null, null, true, false);
        String bomString = Base64.getEncoder().encodeToString("""
                {
                  "bomFormat": "CycloneDX",
                  "specVersion": "1.4",
                  "components": [
                    {
                      "version": "1.2.3"
                    }
                  ]
                }
                """.getBytes());
        BomSubmitRequest request = new BomSubmitRequest(project.getUuid().toString(), null, null, false, bomString);
        Response response = target(V1_BOM).request()
                .header(X_API_KEY, apiKey)
                .put(Entity.entity(request, MediaType.APPLICATION_JSON));
        Assert.assertEquals(400, response.getStatus(), 0);
        Assert.assertEquals("The uploaded CycloneDX BOM is invalid: $.version: is missing but it is required; $.components[0].type: is missing but it is required; $.components[0].name: is missing but it is required", getPlainTextBody(response));
    }

    @Test
    public void uploadInvalidFormatBomTest() throws Exception {
        initializeWithPermissions(Permissions.BOM_UPLOAD);
        Project project = qm.createProject("Acme Example", null, "1.0", null, null, null, true, false);
        File file = new File(IOUtils.resourceToURL("/unit/bom-invalid.json").toURI());
        String bomString = Base64.getEncoder().encodeToString(FileUtils.readFileToByteArray(file));
        BomSubmitRequest request = new BomSubmitRequest(project.getUuid().toString(), null, null, false, bomString);
        Response response = target(V1_BOM).request()
                .header(X_API_KEY, apiKey)
                .put(Entity.entity(request, MediaType.APPLICATION_JSON));
        Assert.assertEquals(400, response.getStatus(), 0);
        Assert.assertEquals("The uploaded CycloneDX file contains malformed JSON or XML", getPlainTextBody(response));
    }

    @Test
    public void uploadBomInvalidProjectTest() throws Exception {
        initializeWithPermissions(Permissions.BOM_UPLOAD);
        File file = new File(IOUtils.resourceToURL("/unit/bom-1.xml").toURI());
        String bomString = Base64.getEncoder().encodeToString(FileUtils.readFileToByteArray(file));
        BomSubmitRequest request = new BomSubmitRequest(UUID.randomUUID().toString(), null, null, false, bomString);
        Response response = target(V1_BOM).request()
                .header(X_API_KEY, apiKey)
                .put(Entity.entity(request, MediaType.APPLICATION_JSON));
        Assert.assertEquals(404, response.getStatus(), 0);
        Assert.assertNull(response.getHeaderString(TOTAL_COUNT_HEADER));
        String body = getPlainTextBody(response);
        Assert.assertEquals("The project could not be found.", body);
    }

    @Test
    public void uploadBomAutoCreateTest() throws Exception {
        initializeWithPermissions(Permissions.BOM_UPLOAD, Permissions.PROJECT_CREATION_UPLOAD);
        File file = new File(IOUtils.resourceToURL("/unit/bom-1.xml").toURI());
        String bomString = Base64.getEncoder().encodeToString(FileUtils.readFileToByteArray(file));
        BomSubmitRequest request = new BomSubmitRequest(null, "Acme Example", "1.0", true, bomString);
        Response response = target(V1_BOM).request()
                .header(X_API_KEY, apiKey)
                .put(Entity.entity(request, MediaType.APPLICATION_JSON));
        Assert.assertEquals(200, response.getStatus(), 0);
        JsonObject json = parseJsonObject(response);
        Assert.assertNotNull(json);
        Assert.assertNotNull(json.getString("token"));
        Assert.assertTrue(UuidUtil.isValidUUID(json.getString("token")));
        Project project = qm.getProject("Acme Example", "1.0");
        Assert.assertNotNull(project);
    }

    @Test
    public void uploadBomUnauthorizedTest() throws Exception {
        File file = new File(IOUtils.resourceToURL("/unit/bom-1.xml").toURI());
        String bomString = Base64.getEncoder().encodeToString(FileUtils.readFileToByteArray(file));
        BomSubmitRequest request = new BomSubmitRequest(null, "Acme Example", "1.0", true, bomString);
        Response response = target(V1_BOM).request()
                .header(X_API_KEY, apiKey)
                .put(Entity.entity(request, MediaType.APPLICATION_JSON));
        Assert.assertEquals(401, response.getStatus(), 0);
        String body = getPlainTextBody(response);
        Assert.assertEquals("The principal does not have permission to create project.", body);
    }

    @Test
    public void uploadBomAutoCreateTestWithParentTest() throws Exception {
        initializeWithPermissions(Permissions.BOM_UPLOAD, Permissions.PROJECT_CREATION_UPLOAD);
        File file = new File(IOUtils.resourceToURL("/unit/bom-1.xml").toURI());
        String bomString = Base64.getEncoder().encodeToString(FileUtils.readFileToByteArray(file));
        // Upload parent project
        BomSubmitRequest request = new BomSubmitRequest(null, "Acme Parent", "1.0", true, bomString);
        Response response = target(V1_BOM).request()
                .header(X_API_KEY, apiKey)
                .put(Entity.entity(request, MediaType.APPLICATION_JSON));
        Assert.assertEquals(200, response.getStatus(), 0);
        JsonObject json = parseJsonObject(response);
        Assert.assertNotNull(json);
        Project parent = qm.getProject("Acme Parent", "1.0");
        Assert.assertNotNull(parent);
        String parentUUID = parent.getUuid().toString();

        // Upload first child, search parent by UUID
        request = new BomSubmitRequest(null, "Acme Example", "1.0", true, parentUUID, null, null, bomString);
        response = target(V1_BOM).request()
                .header(X_API_KEY, apiKey)
                .put(Entity.entity(request, MediaType.APPLICATION_JSON));
        Assert.assertEquals(200, response.getStatus(), 0);
        json = parseJsonObject(response);
        Assert.assertNotNull(json);
        Assert.assertNotNull(json.getString("token"));
        Assert.assertTrue(UuidUtil.isValidUUID(json.getString("token")));
        Project child = qm.getProject("Acme Example", "1.0");
        Assert.assertNotNull(child);
        Assert.assertNotNull(child.getParent());
        Assert.assertEquals(parentUUID, child.getParent().getUuid().toString());


        // Upload second child, search parent by name+ver
        request = new BomSubmitRequest(null, "Acme Example", "2.0", true, null, "Acme Parent", "1.0", bomString);
        response = target(V1_BOM).request()
                .header(X_API_KEY, apiKey)
                .put(Entity.entity(request, MediaType.APPLICATION_JSON));
        Assert.assertEquals(200, response.getStatus(), 0);
        json = parseJsonObject(response);
        Assert.assertNotNull(json);
        Assert.assertNotNull(json.getString("token"));
        Assert.assertTrue(UuidUtil.isValidUUID(json.getString("token")));
        child = qm.getProject("Acme Example", "2.0");
        Assert.assertNotNull(child);
        Assert.assertNotNull(child.getParent());
        Assert.assertEquals(parentUUID, child.getParent().getUuid().toString());

        // Upload third child, specify parent's UUID, name, ver. Name and ver are ignored when UUID is specified.
        request = new BomSubmitRequest(null, "Acme Example", "3.0", true, parentUUID, "Non-existent parent", "1.0", bomString);
        response = target(V1_BOM).request()
                .header(X_API_KEY, apiKey)
                .put(Entity.entity(request, MediaType.APPLICATION_JSON));
        Assert.assertEquals(200, response.getStatus(), 0);
        json = parseJsonObject(response);
        Assert.assertNotNull(json);
        Assert.assertNotNull(json.getString("token"));
        Assert.assertTrue(UuidUtil.isValidUUID(json.getString("token")));
        child = qm.getProject("Acme Example", "3.0");
        Assert.assertNotNull(child);
        Assert.assertNotNull(child.getParent());
        Assert.assertEquals(parentUUID, child.getParent().getUuid().toString());
    }

    @Test
    public void uploadBomInvalidParentTest() throws Exception {
        initializeWithPermissions(Permissions.BOM_UPLOAD, Permissions.PROJECT_CREATION_UPLOAD);
        File file = new File(IOUtils.resourceToURL("/unit/bom-1.xml").toURI());
        String bomString = Base64.getEncoder().encodeToString(FileUtils.readFileToByteArray(file));
        BomSubmitRequest request = new BomSubmitRequest(null, "Acme Example", "1.0", true, UUID.randomUUID().toString(), null, null, bomString);
        Response response = target(V1_BOM).request()
                .header(X_API_KEY, apiKey)
                .put(Entity.entity(request, MediaType.APPLICATION_JSON));
        Assert.assertEquals(404, response.getStatus(), 0);
        String body = getPlainTextBody(response);
        Assert.assertEquals("The parent component could not be found.", body);

        request = new BomSubmitRequest(null, "Acme Example", "2.0", true, null, "Non-existent parent", null, bomString);
        response = target(V1_BOM).request()
                .header(X_API_KEY, apiKey)
                .put(Entity.entity(request, MediaType.APPLICATION_JSON));
        Assert.assertEquals(404, response.getStatus(), 0);
        body = getPlainTextBody(response);
        Assert.assertEquals("The parent component could not be found.", body);
    }

    @Test
    public void isTokenBeingProcessedTrueTest() {
        UUID uuid = UUID.randomUUID();
        WorkflowState workflowState1 = new WorkflowState();
        workflowState1.setParent(null);
        workflowState1.setStep(BOM_CONSUMPTION);
        workflowState1.setStatus(COMPLETED);
        workflowState1.setToken(uuid);
        var workflowState1Persisted = qm.persist(workflowState1);
        WorkflowState workflowState2 = new WorkflowState();
        workflowState2.setParent(workflowState1Persisted);
        workflowState2.setStep(BOM_PROCESSING);
        workflowState2.setStatus(PENDING);
        workflowState2.setToken(uuid);
        qm.persist(workflowState2);

        Response response = target(V1_BOM + "/token/" + uuid).request()
                .header(X_API_KEY, apiKey)
                .get(Response.class);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
        final String jsonResponse = getPlainTextBody(response);
        assertThatJson(jsonResponse)
                .withMatcher("isBeingProcessed", equalTo(true))
                .isEqualTo(json("""
                    {
                        "processing": "${json-unit.matches:isBeingProcessed}"
                    }
                """));
    }

    @Test
    public void isTokenBeingProcessedFalseTest() {
        UUID uuid = UUID.randomUUID();
        WorkflowState workflowState1 = new WorkflowState();
        workflowState1.setParent(null);
        workflowState1.setStep(BOM_CONSUMPTION);
        workflowState1.setStatus(COMPLETED);
        workflowState1.setToken(uuid);
        var workflowState1Persisted = qm.persist(workflowState1);
        WorkflowState workflowState2 = new WorkflowState();
        workflowState2.setParent(workflowState1Persisted);
        workflowState2.setStep(BOM_PROCESSING);
        workflowState2.setStatus(FAILED);
        workflowState2.setToken(uuid);
        qm.persist(workflowState2);

        Response response = target(V1_BOM + "/token/" + uuid).request()
                .header(X_API_KEY, apiKey)
                .get(Response.class);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
        final String jsonResponse = getPlainTextBody(response);
        assertThatJson(jsonResponse)
                .withMatcher("isBeingProcessed", equalTo(false))
                .isEqualTo(json("""
                    {
                        "processing": "${json-unit.matches:isBeingProcessed}"
                    }
                """));
    }
}
