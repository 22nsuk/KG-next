package featurecat.lizzie.gui;

import featurecat.lizzie.gui.VariationTreeSnapshot.Node;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.awt.*;
import java.util.ArrayList;
import java.util.Optional;
import java.util.function.BooleanSupplier;

public class VariationTree implements VariationTreeImage.Renderer {

  private int YSPACING = 20;
  private int XSPACING = 20;
  private int DOT_DIAM = 11; // Should be odd number
  private int CENTER_DIAM = 5;
  private int RING_DIAM = 15;
  private int diam = DOT_DIAM;
  private int rectBorder = 2;
  private int rect_DIAM = 4;
  private final boolean isLargeScaled;
  private final VariationTreeSnapshot snapshot;
  private BooleanSupplier cancelled;

  private ArrayList<Integer> laneUsageList;
  private Node curMove;
  private Rectangle area;
  private Point clickPoint;

  private int maxX;
  private int maxY;
  private int currentX;
  private int currentY;

  public VariationTree() {
    this(VariationTreeSnapshot.current(), () -> false);
  }

  VariationTree(VariationTreeSnapshot snapshot, BooleanSupplier cancelled) {
    this.snapshot = snapshot;
    this.cancelled = cancelled;
    this.isLargeScaled = snapshot.style.largeScaled();
    laneUsageList = new ArrayList<Integer>();
    area = new Rectangle(0, 0, 0, 0);
    clickPoint = new Point(0, 0);
    if (isLargeScaled) {
      YSPACING = 30;
      XSPACING = 30;
      DOT_DIAM = 16; // Should be odd number
      CENTER_DIAM = 8;
      RING_DIAM = 23;
      diam = DOT_DIAM;
      rectBorder = 3;
      rect_DIAM = 6;
    }
  }

  public Optional<Node> drawTree(
      Graphics2D g,
      int posx,
      int posy,
      int startLane,
      int maxposy,
      int minposx,
      Node startNode,
      int variationNumber,
      boolean calc) {
    VariationTreeSnapshot.checkCancelled(cancelled);
    Optional<Node> node = Optional.empty();
    if (!calc) {
      if (startNode.isCurTrunk()) g.setColor(Color.WHITE);
      else g.setColor(new Color(103, 103, 103));
    }

    int depth = startNode.getDepth() + 1;
    int lane = startLane;
    int moveNumber = startNode.moveNumber;
    while (lane < laneUsageList.size() && laneUsageList.get(lane) <= moveNumber + depth) {
      VariationTreeSnapshot.checkCancelled(cancelled);
      laneUsageList.set(lane, moveNumber - 1);
      lane++;
    }
    if (lane >= laneUsageList.size()) {
      laneUsageList.add(0);
    }

    if (variationNumber > 1) laneUsageList.set(lane - 1, moveNumber - 1);
    laneUsageList.set(lane, moveNumber);

    Node cur = startNode;
    int curposx = posx + lane * XSPACING;
    int dotoffset = DOT_DIAM / 2;
    diam = DOT_DIAM;
    int dotoffsety = diam / 2;
    int diff = (DOT_DIAM - diam) / 2;

    if (calc) {
      if (inNode(curposx + dotoffset, posy + dotoffset)) {
        return Optional.of(startNode);
      }
    } else if (lane > 0) {
      if (lane - startLane > 0 || variationNumber > 1) {
        drawLine(
            g,
            curposx + dotoffset,
            posy + dotoffsety,
            curposx + dotoffset - XSPACING,
            posy + dotoffsety - YSPACING,
            minposx);
        drawLine(
            g,
            posx + (startLane - variationNumber) * XSPACING + 2 * dotoffset + 1,
            posy - YSPACING + dotoffsety,
            curposx + dotoffset - XSPACING,
            posy + dotoffsety - YSPACING,
            minposx);
      } else {
        drawLine(
            g,
            curposx + dotoffset,
            posy + dotoffsety,
            curposx + 2 * dotoffset - XSPACING,
            posy + 2 * dotoffsety - YSPACING,
            minposx);
      }
    }

    Color curcolor = null;
    if (!calc) {
      curcolor = g.getColor();
      if (startNode.previous().isPresent()) {
        boolean showComm = snapshot.style.showCommentNodeColor() && !cur.comment.isEmpty();
        if (showComm) {
          g.setColor(snapshot.style.commentNodeColor());
          g.fillOval(
              curposx + (DOT_DIAM + diff - RING_DIAM) / 2,
              posy + (DOT_DIAM + diff - RING_DIAM) / 2,
              RING_DIAM,
              RING_DIAM);
        }
        Color blunderColor = cur.color;
        g.setColor(blunderColor);
        g.fillOval(curposx + diff, posy + diff, diam, diam);
        if (curcolor != Color.WHITE) {
          g.setColor(new Color(0, 0, 0, 39));
          if (showComm)
            g.fillOval(
                curposx + (DOT_DIAM + diff - RING_DIAM) / 2,
                posy + (DOT_DIAM + diff - RING_DIAM) / 2,
                RING_DIAM,
                RING_DIAM);
          else g.fillOval(curposx + diff, posy + diff, diam, diam);
        }
        if (snapshot.style.showVarMove()) {
          g.setFont(new Font(snapshot.style.uiFontName(), Font.PLAIN, isLargeScaled ? 12 : 9));
          g.setColor(Color.WHITE);
          int moveNum = cur.moveMNNumber;
          if (moveNum < 0) {
            Node nodeP = cur;
            int num = 0;
            while (moveNum < 0 && nodeP.previous().isPresent()) {
              VariationTreeSnapshot.checkCancelled(cancelled);
              nodeP = nodeP.previous().get();
              moveNum = nodeP.moveMNNumber;
              num++;
            }
            moveNum = moveNum + num;
          }
          if (moveNum < 10) g.drawString(String.valueOf(moveNum), curposx + 3, posy + diff - 5);
          else
            g.drawString(
                String.valueOf(moveNum), moveNum >= 100 ? curposx - 3 : curposx, posy + diff - 5);
        }
        if (startNode == snapshot.displayNode) {
          g.setColor(Color.BLACK);
          g.fillOval(
              curposx + (DOT_DIAM + diff - CENTER_DIAM) / 2,
              posy + (DOT_DIAM + diff - CENTER_DIAM) / 2,
              CENTER_DIAM,
              CENTER_DIAM);
          currentX = curposx;
          currentY = posy;
        }
      } else {
        g.fillRect(curposx, posy, DOT_DIAM, DOT_DIAM);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(1f));
        g.drawRect(curposx - 1, posy - 1, DOT_DIAM + 1, DOT_DIAM + 1);
        if (cur == snapshot.displayNode) {
          g.setColor(Color.RED);
          g.fillRect(
              curposx + rectBorder, posy + rectBorder, DOT_DIAM - rect_DIAM, DOT_DIAM - rect_DIAM);
        }
      }
      if (curposx + snapshot.style.margin() > maxX)
        maxX = curposx + snapshot.style.margin();
      if (posy + snapshot.style.margin() > maxY)
        maxY = posy + snapshot.style.margin();
      g.setColor(curcolor);
    }

    while (cur.next(true).isPresent()) {
      VariationTreeSnapshot.checkCancelled(cancelled);
      posy += YSPACING;
      cur = cur.next(true).get();
      if (cur.isCurTrunk()) curcolor = Color.WHITE;
      else curcolor = new Color(103, 103, 103);
      if (cur.isEndDummay()) {
        continue;
      }
      if (calc) {
        if (inNode(curposx + dotoffset, posy + dotoffset)) {
          return Optional.of(cur);
        }
      } else if (curposx > minposx && posy > 0) {
        diam = DOT_DIAM;
        dotoffsety = diam / 2;
        diff = (DOT_DIAM - diam) / 2;
        boolean showComm = snapshot.style.showCommentNodeColor() && !cur.comment.isEmpty();
        if (showComm) {
          g.setColor(snapshot.style.commentNodeColor());
          g.fillOval(
              curposx + (DOT_DIAM + diff - RING_DIAM) / 2,
              posy + (DOT_DIAM + diff - RING_DIAM) / 2,
              RING_DIAM,
              RING_DIAM);
        }
        Color blunderColor = cur.color;
        g.setColor(blunderColor);
        g.fillOval(curposx + diff, posy + diff, diam, diam);
        if (curcolor != Color.WHITE) {
          g.setColor(new Color(0, 0, 0, 39));
          if (showComm)
            g.fillOval(
                curposx + (DOT_DIAM + diff - RING_DIAM) / 2,
                posy + (DOT_DIAM + diff - RING_DIAM) / 2,
                RING_DIAM,
                RING_DIAM);
          else g.fillOval(curposx + diff, posy + diff, diam, diam);
        }
        if (cur == snapshot.displayNode) {
          g.setColor(Color.BLACK);
          g.fillOval(
              curposx + (DOT_DIAM + diff - CENTER_DIAM) / 2,
              posy + (DOT_DIAM + diff - CENTER_DIAM) / 2,
              CENTER_DIAM,
              CENTER_DIAM);
          currentX = curposx;
          currentY = posy;
        }
        g.setColor(curcolor);
        g.drawLine(
            curposx + dotoffset,
            posy - 1 + diff,
            curposx + dotoffset,
            posy - YSPACING + dotoffset + (diff > 0 ? dotoffset + 1 : dotoffsety) + 1);
        if (snapshot.style.showVarMove()) {
          g.setFont(new Font(snapshot.style.uiFontName(), Font.PLAIN, isLargeScaled ? 12 : 9));
          g.setColor(Color.WHITE);
          int moveNum = lane == 0 ? cur.moveNumber : cur.moveMNNumber;
          if (moveNum < 0) {
            Node nodeP = cur;
            int num = 0;
            while (moveNum < 0 && nodeP.previous().isPresent()) {
              VariationTreeSnapshot.checkCancelled(cancelled);
              nodeP = nodeP.previous().get();
              moveNum = nodeP.moveMNNumber;
              num++;
            }
            moveNum = moveNum + num;
          }
          if (moveNum < 10) g.drawString(String.valueOf(moveNum), curposx - 7, posy + diff - 1);
          else
            g.drawString(
                String.valueOf(moveNum),
                moveNum >= 100 ? curposx - 13 : curposx - 10,
                posy + diff - 1);
        }
        if (curposx + snapshot.style.margin() > maxX)
          maxX = curposx + snapshot.style.margin();
        if (posy + snapshot.style.margin() > maxY)
          maxY = posy + snapshot.style.margin();

      }

    }
    while (cur.previous().isPresent() && (cur != startNode)) {
      VariationTreeSnapshot.checkCancelled(cancelled);
      cur = cur.previous().get();
      int curwidth = lane;
      for (int i = 1; i < cur.numberOfChildren(); i++) {
        VariationTreeSnapshot.checkCancelled(cancelled);
        curwidth++;
        Optional<Node> variation = cur.getVariation(i);
        if (variation.isPresent()) {
          Optional<Node> subNode =
              drawTree(g, posx, posy, curwidth, maxposy, minposx, variation.get(), i, calc);
          if (calc && subNode.isPresent()) {
            return subNode;
          }
        }
      }
      posy -= YSPACING;
    }
    return node;
  }

  int contentWidth() { return maxX; }
  int contentHeight() { return maxY; }
  int currentX() { return currentX; }
  int currentY() { return currentY; }

  public void draw(Graphics2D g, int posx, int posy, int width, int height) {
    maxX = width;
    maxY = height;
    currentX = 0;
    currentY = 0;
    draw(g, posx, posy, width, height, false);
  }

  public Optional<Node> draw(
      Graphics2D g, int posx, int posy, int width, int height, boolean calc) {
    if (width <= 0 || height <= 0) {
      return Optional.empty(); // we don't have enough space
    }
    if (!calc) area.setBounds(posx, posy, width, height);
    area.setBounds(posx, posy, width, height);

    int middleY = 20; // posy + height / 2;
    int xoffset = 20;
    laneUsageList.clear();

    curMove = snapshot.root;

    int curposy =
        middleY - YSPACING * (curMove.moveNumber - curMove.moveNumber);
    Node node = curMove; // top;
    int lane = getCurLane(node, curMove, curposy, posy + height, 0, true);
    int startx = posx + xoffset;
    if (((lane + 1) * XSPACING + xoffset + DOT_DIAM - width) > 0) {
      startx = startx - ((lane + 1) * XSPACING + xoffset + DOT_DIAM - width);
    }
    return drawTree(g, startx, curposy, 0, posy + height, posx, node, 0, calc);
  }

  private void drawLine(Graphics g, int x1, int y1, int x2, int y2, int minx) {
    if (x1 <= minx && x2 <= minx) {
      return;
    }
    int nx1 = x1, ny1 = y1, nx2 = x2, ny2 = y2;
    if (x1 > minx && x2 <= minx) {
      ny2 = y2 - (x1 - minx) / (x1 - x2) * (y2 - y1);
      nx2 = minx;
    } else if (x2 > minx && x1 <= minx) {
      ny1 = y1 - (x2 - minx) / (x2 - x1) * (y1 - y2);
      nx1 = minx;
    }
    g.drawLine(nx1, ny1, nx2, ny2);
  }

  public boolean inNode(int x, int y) {
    return Math.abs(clickPoint.x - x) <= XSPACING / 2 && Math.abs(clickPoint.y - y) <= YSPACING / 2;
  }

  public void onClicked(int x, int y) {
    nodeAt(x, y).ifPresent(snapshot::navigateTo);
  }

  @Override
  public Optional<BoardHistoryNode> nodeAt(int x, int y) {
    if (!area.contains(x, y)) return Optional.empty();
    // Published renderers are owned by the EDT; hit testing reads their frozen snapshot.
    cancelled = () -> false;
    clickPoint.setLocation(x, y);
    return draw(null, area.x, area.y, area.width, area.height, true).map(node -> node.source);
  }

  private int getCurLane(
      Node start,
      Node curMove,
      int curposy,
      int maxy,
      int laneCount,
      boolean isMain) {
    Node next = start;
    int nexty = curposy;
    while (next.next().isPresent() && nexty + YSPACING < maxy) {
      VariationTreeSnapshot.checkCancelled(cancelled);
      nexty += YSPACING;
      next = next.next().get();
    }
    while (next.previous().isPresent() && (isMain || next != start)) {
      VariationTreeSnapshot.checkCancelled(cancelled);
      next = next.previous().get();
      for (int i = 1; i < next.numberOfChildren(); i++) {
        laneCount++;
        if (next.findIndexOfNode(curMove) == i) {
          return laneCount;
        }
        Optional<Node> variation = next.getVariation(i);
        if (variation.isPresent()) {
          int subLane = getCurLane(variation.get(), curMove, nexty, maxy, laneCount, false);
          if (subLane > 0) {
            return subLane;
          }
        }
      }
      nexty -= YSPACING;
    }
    return 0;
  }
}
