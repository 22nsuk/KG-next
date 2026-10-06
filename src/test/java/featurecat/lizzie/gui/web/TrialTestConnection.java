package featurecat.lizzie.gui.web;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.java_websocket.WebSocket;
import org.json.JSONObject;

/** In-memory transport endpoint; identity and close state are distinct for each client. */
final class TrialTestConnection {
  boolean open = true;
  final List<JSONObject> messages = new ArrayList<>();
  final WebSocket socket =
      (WebSocket)
          Proxy.newProxyInstance(
              WebSocket.class.getClassLoader(),
              new Class<?>[] {WebSocket.class},
              (proxy, method, args) -> {
                switch (method.getName()) {
                  case "isOpen":
                    return open;
                  case "isClosed":
                    return !open;
                  case "hasBufferedData":
                    return false;
                  case "send":
                    messages.add(new JSONObject((String) args[0]));
                    return null;
                  case "close":
                  case "closeConnection":
                    open = false;
                    return null;
                  case "hashCode":
                    return System.identityHashCode(proxy);
                  case "equals":
                    return proxy == args[0];
                  case "toString":
                    return "trial-test-connection";
                  default:
                    throw new AssertionError("Unexpected transport call: " + method.getName());
                }
              });
}
