package featurecat.lizzie.logging;

import jdk.jfr.Category;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** Opt-in durations: no SGF text, filenames or engine commands are recorded. */
@Name("kg.sgf.Phase")
@Label("SGF operation phase")
@Category({"KG-next", "SGF"})
@Enabled(false)
@StackTrace(false)
public final class SgfPhase extends Event implements AutoCloseable {
  @Label("Phase") public final String phase;

  public SgfPhase(String phase) {
    this.phase = phase;
    begin();
  }

  @Override
  public void close() {
    end();
    commit();
  }
}
