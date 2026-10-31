package com.glofox.memberships.storage.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.glofox.memberships.domain.MemberKey;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InMemoryUnlimitedClaimStoreTest {

    private final InMemoryUnlimitedClaimStore store = new InMemoryUnlimitedClaimStore();
    private final UUID p1 = UUID.randomUUID();
    private final UUID p2 = UUID.randomUUID();

    @Test
    @DisplayName("a member may claim the unlimited slot only once")
    void claimOnceOnly() {
        MemberKey alice = new MemberKey("Alice");

        assertThat(store.tryClaim(alice, p1)).isTrue();
        assertThat(store.tryClaim(alice, p2)).isFalse();
    }

    @Test
    @DisplayName("case and whitespace variants collide - normalisation and the map are one mechanism")
    void caseAndWhitespaceCollide() {
        assertThat(store.tryClaim(new MemberKey("Alice"), p1)).isTrue();

        assertThat(store.tryClaim(new MemberKey(" alice "), p2)).isFalse();
        assertThat(store.tryClaim(new MemberKey("ALICE"), p2)).isFalse();
        assertThat(store.tryClaim(new MemberKey("aLiCe"), p2)).isFalse();
    }

    @Test
    @DisplayName("a release from a non-owner does not revoke the holder's claim")
    void releaseOnlyIfOwner() {
        MemberKey alice = new MemberKey("Alice");
        store.tryClaim(alice, p1);

        assertThat(store.release(alice, p2)).as("p2 does not own the claim").isFalse();
        assertThat(store.tryClaim(alice, p2)).as("p1 still holds it").isFalse();
    }

    @Test
    @DisplayName("after the owner releases, the member may claim again")
    void releaseThenReclaim() {
        MemberKey alice = new MemberKey("Alice");
        store.tryClaim(alice, p1);

        assertThat(store.release(alice, p1)).isTrue();
        assertThat(store.tryClaim(alice, p2)).isTrue();
    }
}
