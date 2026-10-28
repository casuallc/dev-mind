package com.devmind.knowledge.access;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.knowledge.KnowledgeAccessPolicy.Action;
import com.devmind.common.knowledge.KnowledgeIdentityProvider;
import com.devmind.common.knowledge.KnowledgeIdentityProvider.Subject;
import com.devmind.knowledge.dto.KnowledgeAccessView;
import com.devmind.knowledge.model.*;
import com.devmind.knowledge.repo.*;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import static org.junit.jupiter.api.Assertions.*;

/** Real H2 + JPA verifies authorization, projection queries, grant replacement and versioning. */
@SpringJUnitConfig(KnowledgeAccessServiceTest.Config.class)
class KnowledgeAccessServiceTest {
    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableJpaRepositories(basePackageClasses = KnowledgeBaseRepository.class)
    @Import({KnowledgeAccessService.class,KnowledgeSourceAuthorizer.class})
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:knowledge-access;DB_CLOSE_DELAY=-1", "sa", "");
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(ds);
            factory.setPackagesToScan("com.devmind.knowledge.model");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            var props = new Properties(); props.setProperty("hibernate.hbm2ddl.auto", "create-drop");
            props.setProperty("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
            factory.setJpaProperties(props); return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
        @Bean AtomicReference<Subject> subject() { return new AtomicReference<>(); }
        @Bean KnowledgeIdentityProvider identities(AtomicReference<Subject> subject) {
            return new KnowledgeIdentityProvider() {
                @Override public Subject current() {
                    if (subject.get() == null) throw new DevMindException(ErrorCode.UNAUTHORIZED, "missing");
                    return subject.get();
                }
                @Override public Subject resolve(String actor) {
                    if (actor == null || !Set.of("owner", "reader", "other", "admin", "viewer").contains(actor))
                        throw new DevMindException(ErrorCode.UNAUTHORIZED, "invalid");
                    return new Subject(actor, actor.equals("admin") ? "ADMIN" : actor.equals("viewer") ? "VIEWER" : "DEVELOPER");
                }
            };
        }
    }

    @Autowired KnowledgeAccessService access;
    @Autowired KnowledgeBaseRepository bases;
    @Autowired KnowledgeEntryRepository entries;
    @Autowired KnowledgePolicyRepository policies;
    @Autowired KnowledgePolicyGrantRepository grants;
    @Autowired AtomicReference<Subject> subject;
    @Autowired PlatformTransactionManager transactions;
    @Autowired KnowledgeAuditRepository audit;
    KnowledgeBaseEntity base;

    @BeforeEach void setup() {
        grants.deleteAll(); entries.deleteAll(); bases.deleteAll(); policies.deleteAll();
        as("owner");
        base = new KnowledgeBaseEntity(); base.setName("private");
        base.setCreatedAt(Instant.now()); base.setUpdatedAt(Instant.now());
        access.initializeBase(base); base = bases.saveAndFlush(base);
    }
    void as(String actor) { subject.set(new Subject(actor, actor.equals("admin") ? "ADMIN" : actor.equals("viewer") ? "VIEWER" : "DEVELOPER")); }
    KnowledgeEntryEntity entry(String name, String source) {
        var e = new KnowledgeEntryEntity(); e.setKbId(base.getId()); e.setName(name);
        e.setSource(source); e.setContentMd("sensitive content"); e.setStatus("active");
        return entries.saveAndFlush(e);
    }
    KnowledgeAccessView.Grant allow(String actor, Action action) {
        return new KnowledgeAccessView.Grant(actor, action, "ALLOW");
    }
    void setAccess(String visibility, KnowledgeAccessView.Grant... rows) {
        var view = access.baseAccess(base.getId());
        access.updateBaseAccess(base.getId(), new KnowledgeAccessView.Update(view.revision(), visibility, List.of(rows)));
    }
    void fails(ErrorCode code, Runnable run) { assertEquals(code, assertThrows(DevMindException.class, run::run).getErrorCode()); }

    @Test void privateByDefaultAndAdminCannotRead() {
        assertEquals(List.of(base.getId()), access.readableBaseIds(null));
        for (var actor : List.of("reader", "admin")) {
            as(actor);
            assertTrue(access.readableBaseIds(null).isEmpty());
            fails(ErrorCode.NOT_FOUND, () -> access.requireBase(base.getId(), Action.READ));
        }
    }
    @Test void explicitReadDoesNotAllowEditingAndRevocationIsImmediate() {
        var e = entry("secret", "file"); setAccess("PRIVATE", allow("reader", Action.READ));
        as("reader"); access.requireEntry(e.getId(), Action.READ);
        fails(ErrorCode.FORBIDDEN, () -> access.requireEntry(e.getId(), Action.EDIT));
        as("owner"); setAccess("PRIVATE"); as("reader");
        fails(ErrorCode.NOT_FOUND, () -> access.requireEntries("reader", List.of(e.getId())));
    }
    @Test void grantsOnlyChangesAdvanceRevisionAndRejectStaleUpdate() {
        var before = access.baseAccess(base.getId());
        var after = access.updateBaseAccess(base.getId(), new KnowledgeAccessView.Update(before.revision(), "PRIVATE", List.of(allow("reader", Action.READ))));
        assertTrue(after.revision() > before.revision());
        fails(ErrorCode.CONFLICT, () -> access.updateBaseAccess(base.getId(), new KnowledgeAccessView.Update(before.revision(), "INTERNAL", List.of())));
        as("reader"); access.requireBase(base.getId(), Action.READ);
    }
    @Test void internalReadDoesNotGrantWritesAndDenyTakesPrecedence() {
        setAccess("INTERNAL", new KnowledgeAccessView.Grant("reader", Action.READ, "DENY"));
        as("reader"); fails(ErrorCode.NOT_FOUND, () -> access.requireBase(base.getId(), Action.READ));
        as("other"); access.requireBase(base.getId(), Action.READ);
        fails(ErrorCode.FORBIDDEN, () -> access.requireBase(base.getId(), Action.EDIT));
    }
    @Test void documentPolicyOnlyNarrowsAndPaginationUsesAllowedIds() {
        var e = entry("restricted", "manual"); setAccess("INTERNAL");
        access.restrictEntry(e.getId(), new KnowledgeAccessView.Update(-1, "PRIVATE", List.of()));
        as("reader"); assertTrue(access.readableEntryIds(List.of(base.getId())).isEmpty());
        fails(ErrorCode.NOT_FOUND, () -> access.requireEntry(e.getId(), Action.READ));
        as("owner");
        var ids = access.readableEntryIds(List.of(base.getId()));
        assertEquals(List.of(e.getId()), ids);
        assertEquals(1, entries.pageSummariesByBase(base.getId(), null, null, null, null, null,
                ids, org.springframework.data.domain.PageRequest.of(0, 10)).getTotalElements());
        setAccess("PRIVATE");
        var view = access.entryAccess(e.getId());
        access.restrictEntry(e.getId(), new KnowledgeAccessView.Update(view.revision(), "INTERNAL", List.of()));
        as("other"); assertTrue(access.readableEntryIds(List.of(base.getId())).isEmpty(), "child cannot expand base");
    }
    @Test void externalSourcesStayQuarantinedAndEmptyScopeNeverMeansAll() {
        entry("external", "feishu"); var local = entry("local", "file");
        assertEquals(List.of(local.getId()), access.readableEntryIds(List.of(base.getId())));
        assertTrue(access.readableBaseIds(List.of()).isEmpty());
        assertTrue(access.readableEntryIds(List.of()).isEmpty());
    }
    @Test void legacyBaseNeedsExplicitAdminAssignmentWithoutReadBypass() {
        var legacy = new KnowledgeBaseEntity(); legacy.setName("legacy"); legacy = bases.saveAndFlush(legacy);
        Long id = legacy.getId();
        fails(ErrorCode.NOT_FOUND, () -> access.requireBase(id, Action.READ));
        fails(ErrorCode.FORBIDDEN, access::pendingBases);
        as("admin"); assertEquals(1, access.pendingBases().size());
        access.assignLegacyOwner(id, "reader");
        fails(ErrorCode.NOT_FOUND, () -> access.requireBase(id, Action.READ));
        as("reader"); access.requireBase(id, Action.READ);
    }
    @Test void missingIdentityNeverFallsBackToLocal() {
        subject.set(null);
        fails(ErrorCode.UNAUTHORIZED, () -> access.readableBaseIds(null));
        fails(ErrorCode.UNAUTHORIZED, () -> access.requireBase((String) null, base.getId(), Action.READ));
    }
    @Test void documentRestrictionCanReturnToInheritanceWithRevisionCheck() {
        var e = entry("restricted", "file"); setAccess("INTERNAL");
        var restricted = access.restrictEntry(e.getId(), new KnowledgeAccessView.Update(-1, "PRIVATE", List.of()));
        as("reader"); fails(ErrorCode.NOT_FOUND, () -> access.requireEntry(e.getId(), Action.READ));
        as("owner");
        fails(ErrorCode.CONFLICT, () -> access.restrictEntry(e.getId(), new KnowledgeAccessView.Update(-1, "INHERIT", List.of())));
        access.restrictEntry(e.getId(), new KnowledgeAccessView.Update(restricted.revision(), "INHERIT", List.of()));
        as("reader"); access.requireEntry(e.getId(), Action.READ);
    }
    @Test void privateKnowledgeDoesNotLeaveForUnisolatedRunner() {
        var e = entry("private", "file");
        assertFalse(access.canMaterializeForRunner(List.of(e.getId())));
        setAccess("INTERNAL"); assertFalse(access.canMaterializeForRunner(List.of(e.getId())),"INTERNAL still requires a verified isolated receiver and lease protocol");
        access.restrictEntry(e.getId(), new KnowledgeAccessView.Update(-1, "PRIVATE", List.of()));
        assertFalse(access.canMaterializeForRunner(List.of(e.getId())));
    }
    @Test void viewerCannotGainWriteThroughResourceGrant() {
        setAccess("PRIVATE", allow("viewer", Action.READ), allow("viewer", Action.EDIT));
        as("viewer"); access.requireBase(base.getId(), Action.READ);
        fails(ErrorCode.FORBIDDEN, () -> access.requireBase(base.getId(), Action.EDIT));
    }
    @Test void recheckDoesNotTrustAnEntityCachedBeforeConcurrentRevocation() {
        setAccess("INTERNAL");
        var outer = new org.springframework.transaction.support.TransactionTemplate(transactions);
        outer.execute(status -> {
            var cached = policies.findById(base.getPolicyId()).orElseThrow();
            as("reader"); assertEquals(List.of(base.getId()), access.readableBaseIds(null));
            var concurrent = new org.springframework.transaction.support.TransactionTemplate(transactions);
            concurrent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            concurrent.execute(other -> {
                var policy = policies.findById(base.getPolicyId()).orElseThrow();
                policy.setVisibility("PRIVATE"); policy.touch(); policies.saveAndFlush(policy);
                return null;
            });
            assertEquals("INTERNAL", cached.getVisibility(), "managed entity is deliberately stale");
            assertTrue(access.readableBaseIds(null).isEmpty(), "authorization must query a fresh scalar snapshot");
            return null;
        });
    }
    @Test void invalidGrantAccountIsBadInputRatherThanExpiredCallerLogin() {
        var before = access.baseAccess(base.getId());
        fails(ErrorCode.BAD_REQUEST, () -> access.updateBaseAccess(base.getId(),
                new KnowledgeAccessView.Update(before.revision(), "INTERNAL", List.of(allow("missing", Action.READ)))));
        assertEquals("PRIVATE", access.baseAccess(base.getId()).visibility());
    }
    @Test void administratorPolicyControlDoesNotImplicitlyReadAndDebugReadExpires() {
        var document=entry("sensitive","manual");as("admin");
        var policy=access.policyControl(base.getId());
        fails(ErrorCode.NOT_FOUND,()->access.requireEntry(document.getId(),Action.READ));
        var debug=access.debugRead(base.getId(),new KnowledgeAccessService.DebugRead(policy.revision(),1,List.of(),"investigate access incident"));
        access.requireEntry(document.getId(),Action.READ);
        assertFalse(debug.effectiveActions().contains(Action.EDIT));
        var temporary=grants.findByPolicyId(base.getPolicyId()).stream().filter(g->g.getPrincipalId().equals("admin")).findFirst().orElseThrow();
        assertNotNull(temporary.getExpiresAt());temporary.setExpiresAt(Instant.now().minusSeconds(1));grants.saveAndFlush(temporary);
        fails(ErrorCode.NOT_FOUND,()->access.requireEntry(document.getId(),Action.READ));
        assertTrue(audit.findTop100ByActorOrderByCreatedAtDesc("admin").stream().anyMatch(e->"DEBUG_READ".equals(e.getAction())));
    }
    @Test void diagnosticGrantCannotOverrideDenyOrSourceQuarantine() {
        setAccess("PRIVATE",new KnowledgeAccessView.Grant("admin",Action.READ,"DENY"));as("admin");
        var deniedPolicy=access.policyControl(base.getId());
        fails(ErrorCode.FORBIDDEN,()->access.debugRead(base.getId(),new KnowledgeAccessService.DebugRead(deniedPolicy.revision(),1,List.of(),"investigate source incident")));
        as("owner");setAccess("PRIVATE");var external=entry("external","feishu");as("admin");var policy=access.policyControl(base.getId());
        access.debugRead(base.getId(),new KnowledgeAccessService.DebugRead(policy.revision(),1,List.of(),"investigate source incident"));
        fails(ErrorCode.NOT_FOUND,()->access.requireEntry(external.getId(),Action.READ));
    }
    @Test void explicitOwnerTransferIsAuditedAndRemovesOldImplicitOwnership() {
        as("admin");var policy=access.policyControl(base.getId());
        access.transferOwner(base.getId(),new KnowledgeAccessService.Transfer(policy.revision(),"reader","approved responsibility transfer"));
        as("owner");fails(ErrorCode.NOT_FOUND,()->access.requireBase(base.getId(),Action.READ));
        as("reader");access.requireBase(base.getId(),Action.MANAGE_ACL);
    }
}
