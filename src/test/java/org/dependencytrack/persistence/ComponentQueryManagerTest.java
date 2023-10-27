package org.dependencytrack.persistence;

import org.dependencytrack.PersistenceCapableTest;
import org.dependencytrack.model.Analysis;
import org.dependencytrack.model.AnalysisJustification;
import org.dependencytrack.model.AnalysisResponse;
import org.dependencytrack.model.AnalysisState;
import org.dependencytrack.model.AnalyzerIdentity;
import org.dependencytrack.model.Component;
import org.dependencytrack.model.DependencyMetrics;
import org.dependencytrack.model.Policy;
import org.dependencytrack.model.PolicyCondition;
import org.dependencytrack.model.PolicyViolation;
import org.dependencytrack.model.Project;
import org.dependencytrack.model.ViolationAnalysis;
import org.dependencytrack.model.ViolationAnalysisState;
import org.dependencytrack.model.Vulnerability;
import org.junit.Test;

import javax.jdo.JDOObjectNotFoundException;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

public class ComponentQueryManagerTest extends PersistenceCapableTest {

    @Test
    public void recursivelyDeleteTest() {
        final var project = new Project();
        project.setName("acme-app");
        project.setVersion("1.0.0");
        qm.persist(project);

        final var component = new Component();
        component.setProject(project);
        component.setName("acme-lib");
        component.setVersion("2.0.0");
        qm.persist(component);

        // Assign a vulnerability and an accompanying analysis with comments to component.
        final var vuln = new Vulnerability();
        vuln.setVulnId("INT-123");
        vuln.setSource(Vulnerability.Source.INTERNAL);
        qm.persist(vuln);
        qm.addVulnerability(vuln, component, AnalyzerIdentity.INTERNAL_ANALYZER);
        final Analysis analysis = qm.makeAnalysis(component, vuln,
                AnalysisState.NOT_AFFECTED,
                AnalysisJustification.CODE_NOT_REACHABLE,
                AnalysisResponse.WORKAROUND_AVAILABLE,
                "analysisDetails", false);
        qm.makeAnalysisComment(analysis, "someComment", "someCommenter");

        // Create a child component to validate that deletion is indeed recursive.
        final var componentChild = new Component();
        componentChild.setProject(project);
        componentChild.setParent(component);
        componentChild.setName("acme-sub-lib");
        componentChild.setVersion("3.0.0");
        qm.persist(componentChild);

        // Assign a policy violation and an accompanying analysis with comments to componentChild.
        final var policy = new Policy();
        policy.setName("Test Policy");
        policy.setViolationState(Policy.ViolationState.WARN);
        policy.setOperator(Policy.Operator.ALL);
        qm.persist(policy);
        final var policyCondition = new PolicyCondition();
        policyCondition.setPolicy(policy);
        policyCondition.setSubject(PolicyCondition.Subject.COORDINATES);
        policyCondition.setOperator(PolicyCondition.Operator.MATCHES);
        policyCondition.setValue("someValue");
        qm.persist(policyCondition);
        final var policyViolation = new PolicyViolation();
        policyViolation.setPolicyCondition(policyCondition);
        policyViolation.setComponent(componentChild);
        policyViolation.setType(PolicyViolation.Type.OPERATIONAL);
        policyViolation.setTimestamp(new Date());
        qm.persist(policyViolation);
        final ViolationAnalysis violationAnalysis = qm.makeViolationAnalysis(componentChild, policyViolation,
                ViolationAnalysisState.REJECTED, false);
        qm.makeViolationAnalysisComment(violationAnalysis, "someComment", "someCommenter");

        // Create metrics for component.
        final var metrics = new DependencyMetrics();
        metrics.setProject(project);
        metrics.setComponent(component);
        metrics.setFirstOccurrence(new Date());
        metrics.setLastOccurrence(new Date());
        qm.persist(metrics);

        assertThatNoException()
                .isThrownBy(() -> qm.recursivelyDelete(component, false));

        // Ensure everything has been deleted as expected.
        assertThat(qm.getAllComponents(project)).isEmpty();
        assertThatExceptionOfType(JDOObjectNotFoundException.class).isThrownBy(() -> qm.getObjectById(Component.class, component.getId()));
        assertThatExceptionOfType(JDOObjectNotFoundException.class).isThrownBy(() -> qm.getObjectById(Component.class, componentChild.getId()));
        assertThatExceptionOfType(JDOObjectNotFoundException.class).isThrownBy(() -> qm.getObjectById(DependencyMetrics.class, metrics.getId()));

        // Ensure associated objects were NOT deleted.
        assertThatNoException().isThrownBy(() -> qm.getObjectById(Project.class, project.getId()));
        assertThatNoException().isThrownBy(() -> qm.getObjectById(Vulnerability.class, vuln.getId()));
        assertThatNoException().isThrownBy(() -> qm.getObjectById(PolicyCondition.class, policyCondition.getId()));
        assertThatNoException().isThrownBy(() -> qm.getObjectById(Policy.class, policy.getId()));
    }

    @Test
    public void testGetComponentsByPurl() {
        final var project = new Project();
        project.setName("acme-app");
        project.setVersion("1.0.0");
        qm.persist(project);

        final var component1 = new Component();
        component1.setProject(project);
        component1.setName("acme-lib-a");
        component1.setVersion("1.0.1");
        component1.setPurl("pkg:maven/foo/bar@1.2.3");
        component1.setPurlCoordinates("pkg:maven/foo/bar@1.2.3");
        component1.setMd5("098f6bcd4621d373cade4e832627b4f6");
        component1.setSha1("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3");
        qm.persist(component1);

        final var component2 = new Component();
        component2.setProject(project);
        component2.setProject(project);
        component2.setName("acme-lib");
        component2.setVersion("1.0.1");
        component2.setPurl("pkg:maven/foo/bar@1.2.3");
        component2.setPurlCoordinates("pkg:maven/foo/bar@1.2.3");
        component2.setMd5("098f6bcd4621d373cade4e832627b4f6");
        component2.setSha1("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3");
        qm.persist(component2);

        List<Component> components = qm.getComponentsByPurl("pkg:maven/foo/bar@1.2.3");
        assertThat(components).isNotNull();
        assertThat(components).hasSize(2);
        assertThat(components).satisfiesExactlyInAnyOrder(component -> {
                    assertThat(component.getMd5()).isEqualTo("098f6bcd4621d373cade4e832627b4f6");
                    assertThat(component.getSha1()).isEqualTo("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3");
                },
                component -> {
                    assertThat(component.getMd5()).isEqualTo("098f6bcd4621d373cade4e832627b4f6");
                    assertThat(component.getSha1()).isEqualTo("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3");
                });
    }
}