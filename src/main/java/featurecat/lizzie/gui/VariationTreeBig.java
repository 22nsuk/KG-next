package featurecat.lizzie.gui;

import featurecat.lizzie.gui.VariationTreeSnapshot.Node;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.awt.*;
import java.util.ArrayList;
import java.util.Optional;
import java.util.function.BooleanSupplier;

public class VariationTreeBig implements VariationTreeImage.Renderer {

  private int YSPACING = 20;
  private int XSPACING = 20;
  private int DOT_DIAM = 11; // Should be odd number
  private int CENTER_DIAM = 5;
  private int RING_DIAM = 15;
  private int rectBorder = 2;
  private int rect_DIAM = 4;
  private int diam = DOT_DIAM;
  private final boolean isLargeScaled;
  private final VariationTreeSnapshot snapshot;
  private BooleanSupplier cancelled;

  private ArrayList<Integer> laneUsageList;
  private Node curMove;
  private Rectangle area;
  private Point clickPoint;
  private int curMoveLane = 0;
  private int maxLane = 0;

  public VariationTreeBig() {
    this(VariationTreeSnapshot.current(), () -> false);
  }

  VariationTreeBig(VariationTreeSnapshot snapshot, BooleanSupplier cancelled) {
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

    if (lane > maxLane) maxLane = lane;
    if (startNode == curMove) curMoveLane = lane;
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
      if (curposx > minposx && posy > 0) {
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
          if (startNode == curMove) {
            if (blunderColor != Color.WHITE) g.setColor(reverseColor(blunderColor));
            else g.setColor(Color.RED);
            g.fillOval(
                curposx + (DOT_DIAM + diff - CENTER_DIAM) / 2 - 1,
                posy + (DOT_DIAM + diff - CENTER_DIAM) / 2 - 1,
                CENTER_DIAM + 2,
                CENTER_DIAM + 2);
          }
        } else {
          g.fillRect(curposx, posy, DOT_DIAM, DOT_DIAM);
          g.setColor(Color.BLACK);
          g.setStroke(new BasicStroke(1f));
          g.drawRect(curposx - 1, posy - 1, DOT_DIAM + 1, DOT_DIAM + 1);
          if (cur == curMove) {
            g.setColor(Color.RED);
            g.fillRect(
                curposx + rectBorder,
                posy + rectBorder,
                DOT_DIAM - rect_DIAM,
                DOT_DIAM - rect_DIAM);
          }
        }
      }
      g.setColor(curcolor);
    }

    while (cur.next(true).isPresent() && posy + YSPACING < maxposy) {
      VariationTreeSnapshot.checkCancelled(cancelled);
      posy += YSPACING;
      cur = cur.next(true).get();
      if (cur.isCurTrunk()) curcolor = Color.WHITE;
      else curcolor = new Color(103, 103, 103);
      if (cur.isEndDummay()) {
        continue;
      }
      if (cur == curMove) curMoveLane = lane;
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
        if (cur == curMove) {
          if (blunderColor != Color.WHITE) g.setColor(reverseColor(blunderColor));
          else g.setColor(Color.RED);
          g.fillOval(
              curposx + (DOT_DIAM + diff - CENTER_DIAM) / 2 - 1,
              posy + (DOT_DIAM + diff - CENTER_DIAM) / 2 - 1,
              CENTER_DIAM + 2,
              CENTER_DIAM + 2);
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

  public void draw(Graphics2D g, int posx, int posy, int width, int height) {
    maxLane = 0;
    draw(g, posx, posy, width, height, false);
  }

  public Optional<Node> draw(
      Graphics2D g, int posx, int posy, int width, int height, boolean calc) {
    if (width <= 0 || height <= 0) {
      return Optional.empty(); // we don't have enough space
    }
    area.setBounds(posx, posy, width, height);
    if (!calc) {
      int DUMMY = Integer.MIN_VALUE / 2;
      clickPoint.setLocation(DUMMY, DUMMY);
      curMoveLane = 0;
      draw(g, posx, posy, width, height, true); // set curMoveLane as a side effect
    }
    int lane = curMoveLane;

    if (!calc) g.setStroke(new BasicStroke(1));
    int middleY = posy + height / 2;
    int xoffset = 20;
    laneUsageList.clear();

    curMove = snapshot.displayNode;
    Node top = curMove.findTop();
    int curposy = middleY - YSPACING * (curMove.moveNumber - top.moveNumber);
    Node node = top;
    while (curposy > posy - YSPACING && node.previous().isPresent()) {
      VariationTreeSnapshot.checkCancelled(cancelled);
      node = node.previous().get();
      curposy -= YSPACING;
    }
    int startx = posx + xoffset;
    if (((lane + 1) * XSPACING + xoffset + DOT_DIAM - width) > 0) {
      startx = startx - ((lane + 1) * XSPACING + xoffset + DOT_DIAM - width);
    }
    if (maxLane * XSPACING + xoffset + DOT_DIAM > width) {
      if ((maxLane - lane) * XSPACING < (width - xoffset - DOT_DIAM) / 2)
        startx = startx - (maxLane - lane) * XSPACING;
      else if (lane * XSPACING + xoffset + DOT_DIAM > width)
        startx = startx - (width - xoffset - DOT_DIAM) / 2;
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

  private Color reverseColor(Color color) {
    int r = color.getRed();
    int g = color.getGreen();
    int b = color.getBlue();
    int r_ = 255 - r;
    int g_ = 255 - g;
    int b_ = 255 - b;
    Color newColor = new Color(r_, g_, b_);
    return newColor;
  }
}
