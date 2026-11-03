package de.agiledev.agent;
import de.agiledev.agent.auth.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import static org.junit.jupiter.api.Assertions.*;
class ModeStoreTest {
 @Test void modesAreSessionScoped() {
  var store=new SessionModeStore();
  var alice=new MockHttpSession();var bob=new MockHttpSession();
  store.set(alice,DemoMode.OVERPRIVILEGED);
  assertEquals(DemoMode.OVERPRIVILEGED,store.get(alice));
  assertEquals(DemoMode.DELEGATED,store.get(bob));
 }
}
