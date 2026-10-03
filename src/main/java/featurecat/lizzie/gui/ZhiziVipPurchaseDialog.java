package featurecat.lizzie.gui;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import featurecat.lizzie.analysis.remote.ZhiziAccountService;
import featurecat.lizzie.analysis.remote.ZhiziApiClient;
import featurecat.lizzie.analysis.remote.ZhiziApiException;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.MessageFormat;
import java.util.List;
import java.util.MissingResourceException;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

/** Safe WeChat Native Pay flow backed by Zhizi's official API. */
final class ZhiziVipPurchaseDialog extends JDialog {
  private static final String CHOICE_CARD = "choice";
  private static final String PAYMENT_CARD = "payment";
  private static final String TOP_UP_MODE = "top-up";
  private static final String VIP_MODE = "vip";
  private static final Color BACKGROUND = new Color(250, 247, 240);
  private static final Color CARD = new Color(255, 253, 248);
  private static final Color BORDER = new Color(221, 211, 190);
  private static final Color TEXT = new Color(43, 39, 31);
  private static final Color MUTED = new Color(112, 104, 90);
  private static final Color GREEN = new Color(43, 139, 90);
  private static final Color ERROR = new Color(190, 69, 56);
  private static final int SETTLEMENT_ATTEMPTS = 6;
  private static final long SETTLEMENT_RETRY_MILLIS = 2000L;

  private final ZhiziApiClient apiClient;
  private final ZhiziAccountService accountService;
  private final String accountToken;
  private final Runnable paymentCompleted;
  private final CardLayout cardLayout = new CardLayout();
  private final JPanel cards = new JPanel(cardLayout);
  private final CardLayout selectionLayout = new CardLayout();
  private final JPanel selectionCards = new JPanel(selectionLayout);
  private final JToggleButton topUpModeButton =
      modeButton(text("RemoteCompute.payment.topUp", "Balance top-up"));
  private final JToggleButton vipModeButton =
      modeButton(text("RemoteCompute.payment.vip", "VIP membership"));
  private final JComboBox<TopUpItem> topUpBox = new JComboBox<>();
  private final JComboBox<ProductItem> productBox = new JComboBox<>();
  private final JLabel topUpSummary = new JLabel(" ");
  private final JLabel topUpStatus = new JLabel(" ");
  private final JLabel productSummary = new JLabel(" ");
  private final JLabel productStatus = new JLabel(" ");
  private final JLabel paymentProduct = new JLabel(" ");
  private final JLabel paymentAmount = new JLabel(" ");
  private final JLabel paymentStatus = new JLabel(" ");
  private final JLabel qrCode = new JLabel();
  private final JButton createOrderButton =
      primaryButton(text("RemoteCompute.payment.createOrder", "Continue to WeChat Pay"));
  private final JButton retryButton =
      secondaryButton(text("RemoteCompute.payment.newOrder", "Create a new order"));
  private final JButton closeButton = secondaryButton(text("RemoteCompute.payment.close", "Close"));
  private final PaymentFlow paymentFlow = new PaymentFlow();

  private SwingWorker<List<ZhiziApiClient.MembershipProduct>, Void> productsWorker;
  private SwingWorker<PreparedOrder, Void> createWorker;
  private SwingWorker<ZhiziApiClient.PaymentOrder, Void> pollWorker;
  private SwingWorker<ZhiziAccountService.PaymentVerification, Void> settlementWorker;
  private Timer pollTimer;
  private ZhiziAccountService.PaymentBaseline paymentBaseline;
  private boolean reauthenticationRequired;
  private boolean completionNotified;
  private int consecutivePollFailures;

  ZhiziVipPurchaseDialog(
      Frame owner,
      ZhiziApiClient apiClient,
      ZhiziAccountService accountService,
      String accountToken,
      Runnable paymentCompleted) {
    super(owner, text("RemoteCompute.payment.title", "Zhizi payments"), true);
    this.apiClient = apiClient;
    this.accountService = accountService;
    this.accountToken = accountToken == null ? "" : accountToken.trim();
    this.paymentCompleted = paymentCompleted == null ? () -> {} : paymentCompleted;
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    setMinimumSize(new Dimension(650, 500));
    setPreferredSize(new Dimension(720, 540));
    setContentPane(buildContent());
    configureActions();
    AccessibilitySupport.installEscapeToClose(getRootPane(), this);
    pack();
    setLocationRelativeTo(owner);
    LizzieFrame.constrainWindowToAvailableWorkArea(this);
    initializeTopUps();
    loadProducts();
  }

  private JPanel buildContent() {
    JPanel root = new JPanel(new BorderLayout(0, 18));
    root.setBackground(BACKGROUND);
    root.setBorder(new EmptyBorder(24, 28, 22, 28));

    JPanel heading = new JPanel();
    heading.setOpaque(false);
    heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
    JLabel title = new JLabel(text("RemoteCompute.payment.title", "Zhizi payments"));
    title.setForeground(TEXT);
    title.setFont(title.getFont().deriveFont(Font.BOLD, 26F));
    title.setAlignmentX(Component.LEFT_ALIGNMENT);
    JLabel subtitle =
        new JLabel(
            text(
                "RemoteCompute.payment.subtitle",
                "Top up your balance or choose a VIP plan, then scan with WeChat."));
    subtitle.setForeground(MUTED);
    subtitle.setFont(subtitle.getFont().deriveFont(14F));
    subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
    heading.add(title);
    heading.add(Box.createVerticalStrut(5));
    heading.add(subtitle);
    root.add(heading, BorderLayout.NORTH);

    cards.setOpaque(false);
    cards.add(buildChoiceCard(), CHOICE_CARD);
    cards.add(buildPaymentCard(), PAYMENT_CARD);
    root.add(cards, BorderLayout.CENTER);
    return root;
  }

  private JPanel buildChoiceCard() {
    JPanel panel = cardPanel();
    panel.setLayout(new GridBagLayout());
    GridBagConstraints gbc = constraints();

    JPanel modes = new JPanel(new java.awt.GridLayout(1, 2, 8, 0));
    modes.setOpaque(false);
    ButtonGroup modeGroup = new ButtonGroup();
    modeGroup.add(topUpModeButton);
    modeGroup.add(vipModeButton);
    topUpModeButton.setSelected(true);
    modes.add(topUpModeButton);
    modes.add(vipModeButton);
    panel.add(modes, gbc);

    gbc.gridy++;
    gbc.insets = new Insets(18, 0, 0, 0);
    selectionCards.setOpaque(false);
    selectionCards.add(buildTopUpSelection(), TOP_UP_MODE);
    selectionCards.add(buildVipSelection(), VIP_MODE);
    panel.add(selectionCards, gbc);

    gbc.gridy++;
    gbc.insets = new Insets(24, 0, 0, 0);
    JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
    actions.setOpaque(false);
    stylePrimary(createOrderButton);
    JButton cancel = secondaryButton(text("RemoteCompute.payment.cancel", "Cancel"));
    styleSecondary(cancel);
    cancel.addActionListener(event -> dispose());
    actions.add(cancel);
    actions.add(createOrderButton);
    panel.add(actions, gbc);

    gbc.gridy++;
    gbc.weighty = 1;
    panel.add(Box.createVerticalGlue(), gbc);
    return panel;
  }

  private JPanel buildTopUpSelection() {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setOpaque(false);
    GridBagConstraints gbc = constraints();
    JLabel label = new JLabel(text("RemoteCompute.payment.topUpAmount", "Top-up amount"));
    label.setForeground(TEXT);
    label.setFont(label.getFont().deriveFont(Font.BOLD, 15F));
    AccessibilitySupport.labelFor(
        label,
        topUpBox,
        text("RemoteCompute.payment.topUpAmountDescription", "Choose a fixed top-up amount."));
    panel.add(label, gbc);

    gbc.gridy++;
    gbc.insets = new Insets(8, 0, 0, 0);
    topUpBox.setPreferredSize(new Dimension(520, 48));
    topUpBox.setRenderer(new TopUpRenderer());
    panel.add(topUpBox, gbc);

    gbc.gridy++;
    gbc.insets = new Insets(14, 0, 0, 0);
    topUpSummary.setForeground(TEXT);
    topUpSummary.setFont(topUpSummary.getFont().deriveFont(Font.BOLD, 18F));
    panel.add(topUpSummary, gbc);

    gbc.gridy++;
    gbc.insets = new Insets(8, 0, 0, 0);
    topUpStatus.setForeground(MUTED);
    topUpStatus.setFont(topUpStatus.getFont().deriveFont(13F));
    topUpStatus.setText(
        text(
            "RemoteCompute.payment.topUpNotice",
            "The amount is credited to your Zhizi balance for on-demand compute."));
    panel.add(topUpStatus, gbc);
    return panel;
  }

  private JPanel buildVipSelection() {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setOpaque(false);
    GridBagConstraints gbc = constraints();
    JLabel label = new JLabel(text("RemoteCompute.payment.plan", "Official VIP plan"));
    label.setForeground(TEXT);
    label.setFont(label.getFont().deriveFont(Font.BOLD, 15F));
    AccessibilitySupport.labelFor(
        label,
        productBox,
        text("RemoteCompute.payment.planDescription", "Select an official Zhizi VIP plan."));
    panel.add(label, gbc);

    gbc.gridy++;
    gbc.insets = new Insets(8, 0, 0, 0);
    productBox.setPreferredSize(new Dimension(520, 48));
    productBox.setRenderer(new ProductRenderer());
    productBox.setEnabled(false);
    panel.add(productBox, gbc);

    gbc.gridy++;
    gbc.insets = new Insets(14, 0, 0, 0);
    productSummary.setForeground(TEXT);
    productSummary.setFont(productSummary.getFont().deriveFont(Font.BOLD, 18F));
    panel.add(productSummary, gbc);

    gbc.gridy++;
    gbc.insets = new Insets(8, 0, 0, 0);
    productStatus.setForeground(MUTED);
    productStatus.setFont(productStatus.getFont().deriveFont(13F));
    panel.add(productStatus, gbc);
    return panel;
  }

  private JPanel buildPaymentCard() {
    JPanel panel = cardPanel();
    panel.setLayout(new BorderLayout(22, 0));

    qrCode.setPreferredSize(new Dimension(276, 276));
    qrCode.setHorizontalAlignment(JLabel.CENTER);
    qrCode.setVerticalAlignment(JLabel.CENTER);
    qrCode.setOpaque(true);
    qrCode.setBackground(Color.WHITE);
    qrCode.setBorder(BorderFactory.createLineBorder(BORDER));
    AccessibilitySupport.named(
        qrCode,
        text("RemoteCompute.payment.qrName", "WeChat payment QR code"),
        text("RemoteCompute.payment.qrDescription", "Scan this code with WeChat to pay."));
    panel.add(qrCode, BorderLayout.WEST);

    JPanel details = new JPanel();
    details.setOpaque(false);
    details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
    JLabel scan = new JLabel(text("RemoteCompute.payment.scan", "Scan with WeChat"));
    scan.setForeground(TEXT);
    scan.setFont(scan.getFont().deriveFont(Font.BOLD, 23F));
    scan.setAlignmentX(Component.LEFT_ALIGNMENT);
    details.add(scan);
    details.add(Box.createVerticalStrut(14));
    paymentProduct.setForeground(TEXT);
    paymentProduct.setFont(paymentProduct.getFont().deriveFont(Font.BOLD, 16F));
    paymentProduct.setAlignmentX(Component.LEFT_ALIGNMENT);
    details.add(paymentProduct);
    details.add(Box.createVerticalStrut(8));
    paymentAmount.setForeground(GREEN);
    paymentAmount.setFont(paymentAmount.getFont().deriveFont(Font.BOLD, 24F));
    paymentAmount.setAlignmentX(Component.LEFT_ALIGNMENT);
    details.add(paymentAmount);
    details.add(Box.createVerticalStrut(16));
    paymentStatus.setForeground(MUTED);
    paymentStatus.setFont(paymentStatus.getFont().deriveFont(Font.BOLD, 13F));
    paymentStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
    details.add(paymentStatus);
    details.add(Box.createVerticalStrut(18));
    JLabel provider =
        new JLabel(
            "<html>"
                + text(
                    "RemoteCompute.payment.providerNotice",
                    "Compute service and payment are provided by Zhizi. KG-next does not store payment details.")
                + "</html>");
    provider.setForeground(MUTED);
    provider.setFont(provider.getFont().deriveFont(12.5F));
    provider.setAlignmentX(Component.LEFT_ALIGNMENT);
    details.add(provider);
    details.add(Box.createVerticalGlue());

    retryButton.setVisible(false);
    styleSecondary(retryButton);
    closeButton.setText(text("RemoteCompute.payment.close", "Close"));
    styleSecondary(closeButton);
    JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    actions.setOpaque(false);
    actions.setAlignmentX(Component.LEFT_ALIGNMENT);
    actions.add(retryButton);
    actions.add(closeButton);
    details.add(actions);
    panel.add(details, BorderLayout.CENTER);
    return panel;
  }

  private void configureActions() {
    topUpModeButton.addActionListener(event -> selectMode(TOP_UP_MODE));
    vipModeButton.addActionListener(event -> selectMode(VIP_MODE));
    topUpBox.addActionListener(event -> showSelectedTopUp());
    productBox.addActionListener(event -> showSelectedProduct());
    createOrderButton.addActionListener(event -> confirmAndCreateOrder());
    retryButton.addActionListener(
        event -> {
          if (reauthenticationRequired) {
            dispose();
          } else {
            resetForNewOrder();
          }
        });
    closeButton.addActionListener(event -> dispose());
    AccessibilitySupport.button(
        createOrderButton,
        createOrderButton.getText(),
        text(
            "RemoteCompute.payment.createOrderDescription", "Review and create one WeChat order."));
    AccessibilitySupport.button(retryButton, retryButton.getText(), retryButton.getText());
    AccessibilitySupport.button(closeButton, closeButton.getText(), closeButton.getText());
  }

  private void initializeTopUps() {
    for (long amountFen : ZhiziApiClient.BALANCE_TOP_UP_PRESETS_FEN) {
      topUpBox.addItem(new TopUpItem(amountFen));
    }
    showSelectedTopUp();
    selectMode(TOP_UP_MODE);
  }

  private void selectMode(String mode) {
    if (!paymentFlow.isSelecting()) {
      return;
    }
    boolean topUp = TOP_UP_MODE.equals(mode);
    topUpModeButton.setSelected(topUp);
    vipModeButton.setSelected(!topUp);
    selectionLayout.show(selectionCards, topUp ? TOP_UP_MODE : VIP_MODE);
    updateModeStyle(topUpModeButton, topUp);
    updateModeStyle(vipModeButton, !topUp);
    updateCreateButtonState();
  }

  private void loadProducts() {
    productStatus.setText(
        text("RemoteCompute.payment.loadingProducts", "Loading official plans..."));
    productBox.setEnabled(false);
    updateCreateButtonState();
    productsWorker =
        new SwingWorker<List<ZhiziApiClient.MembershipProduct>, Void>() {
          @Override
          protected List<ZhiziApiClient.MembershipProduct> doInBackground() throws Exception {
            return apiClient.fetchMembershipProducts();
          }

          @Override
          protected void done() {
            try {
              if (isCancelled()) {
                return;
              }
              List<ZhiziApiClient.MembershipProduct> products = get();
              productBox.removeAllItems();
              for (ZhiziApiClient.MembershipProduct product : products) {
                productBox.addItem(new ProductItem(product));
              }
              boolean available = productBox.getItemCount() > 0;
              productBox.setEnabled(available && paymentFlow.isSelecting());
              productStatus.setForeground(MUTED);
              productStatus.setText(
                  available
                      ? text(
                          "RemoteCompute.payment.livePrice",
                          "The price is loaded from Zhizi immediately before purchase.")
                      : text(
                          "RemoteCompute.payment.noProducts",
                          "No official VIP plan is currently available. Use the Zhizi app instead."));
              showSelectedProduct();
              updateCreateButtonState();
            } catch (Exception failure) {
              productBox.setEnabled(false);
              productStatus.setForeground(ERROR);
              productStatus.setText(
                  text(
                      "RemoteCompute.payment.productsFailed",
                      "Could not load official plans. No order was created."));
              updateCreateButtonState();
            } finally {
              productsWorker = null;
            }
          }
        };
    productsWorker.execute();
  }

  private void showSelectedTopUp() {
    TopUpItem selected = (TopUpItem) topUpBox.getSelectedItem();
    topUpSummary.setText(
        selected == null
            ? " "
            : format(
                "RemoteCompute.payment.topUpSummary",
                "Add {0} to your Zhizi balance",
                formatFen(selected.amountFen)));
    updateCreateButtonState();
  }

  private void showSelectedProduct() {
    ProductItem selected = (ProductItem) productBox.getSelectedItem();
    if (selected == null) {
      productSummary.setText(" ");
      return;
    }
    productSummary.setText(
        format(
            "RemoteCompute.payment.summary",
            "{0} · {1}",
            monthsText(selected.product.durationMonths),
            formatFen(selected.product.priceFen)));
    updateCreateButtonState();
  }

  private void confirmAndCreateOrder() {
    PaymentChoice choice = selectedChoice();
    if (choice == null || !paymentFlow.isSelecting()) {
      return;
    }
    String confirmation =
        "<html><b>"
            + text("RemoteCompute.payment.confirmTitle", "Confirm payment order")
            + "</b><br><br>"
            + choice.confirmationLine()
            + "<br>"
            + format(
                "RemoteCompute.payment.confirmAmount", "Amount: {0}", formatFen(choice.amountFen))
            + "<br>"
            + text("RemoteCompute.payment.confirmMethod", "Payment: WeChat Pay")
            + "<br><br>"
            + text("RemoteCompute.payment.confirmProvider", "Service is provided by Zhizi.")
            + "</html>";
    int answer =
        JOptionPane.showConfirmDialog(
            this,
            confirmation,
            text("RemoteCompute.payment.confirmTitle", "Confirm payment order"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.QUESTION_MESSAGE);
    if (answer != JOptionPane.OK_OPTION) {
      return;
    }
    createOrder(choice);
  }

  private void createOrder(PaymentChoice choice) {
    if (!paymentFlow.beginCreate(choice)) {
      return;
    }
    setSelectionControlsEnabled(false);
    JLabel status = choice.isTopUp() ? topUpStatus : productStatus;
    status.setForeground(MUTED);
    status.setText(text("RemoteCompute.payment.creating", "Creating one secure order..."));
    createWorker =
        new SwingWorker<PreparedOrder, Void>() {
          @Override
          protected PreparedOrder doInBackground() throws Exception {
            ZhiziAccountService.PaymentBaseline baseline =
                accountService.capturePaymentBaseline(accountToken, choice.purpose);
            ZhiziApiClient.PaymentOrder order =
                choice.isTopUp()
                    ? apiClient.createBalanceTopUpOrder(accountToken, choice.amountFen)
                    : apiClient.createMembershipOrder(accountToken, choice.product, false);
            return new PreparedOrder(order, baseline);
          }

          @Override
          protected void done() {
            try {
              if (isCancelled()) {
                return;
              }
              showOrder(get(), choice);
            } catch (Exception failure) {
              paymentFlow.createFailed();
              ZhiziApiException apiError = findApiError(failure);
              if (apiError != null && apiError.isUnauthorized()) {
                RemoteComputeConfig.invalidateZhiziToken();
              }
              status.setForeground(ERROR);
              status.setText(
                  text(
                      "RemoteCompute.payment.createFailed",
                      "The order result is uncertain. It was not retried. Check Zhizi before trying again."));
              setSelectionControlsEnabled(true);
            } finally {
              createWorker = null;
            }
          }
        };
    createWorker.execute();
  }

  private void showOrder(PreparedOrder prepared, PaymentChoice choice) throws Exception {
    ZhiziApiClient.PaymentOrder order = prepared.order;
    ImageIcon paymentQr =
        order.status == ZhiziApiClient.PaymentStatus.PENDING
            ? new ImageIcon(renderQr(order.codeUrl, 276))
            : null;
    paymentFlow.orderCreated(order);
    paymentBaseline = prepared.baseline;
    paymentProduct.setText(choice.displayName);
    paymentAmount.setText(formatFen(order.amountFen));
    qrCode.setIcon(paymentQr);
    cardLayout.show(cards, PAYMENT_CARD);
    if (order.status == ZhiziApiClient.PaymentStatus.SUCCESS) {
      finishSuccess();
    } else if (order.status == ZhiziApiClient.PaymentStatus.FAIL) {
      finishFailure();
    } else {
      paymentStatus.setForeground(MUTED);
      paymentStatus.setText(
          text("RemoteCompute.payment.waiting", "Waiting for payment confirmation..."));
      startPolling();
    }
  }

  private void startPolling() {
    stopPolling();
    consecutivePollFailures = 0;
    pollTimer = new Timer(2000, event -> pollOrder());
    pollTimer.setInitialDelay(2000);
    pollTimer.start();
  }

  private void pollOrder() {
    ZhiziApiClient.PaymentOrder currentOrder = paymentFlow.order();
    if (currentOrder == null
        || !paymentFlow.isPending()
        || pollWorker != null
        || !isDisplayable()) {
      return;
    }
    String expectedOrderId = currentOrder.id;
    pollWorker =
        new SwingWorker<ZhiziApiClient.PaymentOrder, Void>() {
          @Override
          protected ZhiziApiClient.PaymentOrder doInBackground() throws Exception {
            return apiClient.fetchOrder(accountToken, expectedOrderId);
          }

          @Override
          protected void done() {
            try {
              if (isCancelled()
                  || paymentFlow.order() == null
                  || !expectedOrderId.equals(paymentFlow.order().id)) {
                return;
              }
              ZhiziApiClient.PaymentOrder updated = get();
              paymentFlow.orderUpdated(updated);
              consecutivePollFailures = 0;
              if (pollTimer != null) {
                pollTimer.setDelay(2000);
              }
              if (updated.status == ZhiziApiClient.PaymentStatus.SUCCESS) {
                finishSuccess();
              } else if (updated.status == ZhiziApiClient.PaymentStatus.FAIL) {
                finishFailure();
              } else {
                paymentStatus.setForeground(MUTED);
                paymentStatus.setText(
                    text("RemoteCompute.payment.waiting", "Waiting for payment confirmation..."));
              }
            } catch (Exception failure) {
              ZhiziApiException apiError = findApiError(failure);
              if (apiError != null && "invalid_response".equals(apiError.errorKey())) {
                if (pollTimer != null) {
                  pollTimer.stop();
                  pollTimer = null;
                }
                paymentStatus.setForeground(ERROR);
                paymentStatus.setText(
                    text(
                        "RemoteCompute.payment.orderMismatch",
                        "The order details changed unexpectedly. Polling stopped; check Zhizi before creating another order."));
                return;
              }
              consecutivePollFailures++;
              if (pollTimer != null) {
                pollTimer.setDelay(Math.min(10000, 2000 * (consecutivePollFailures + 1)));
              }
              paymentStatus.setForeground(ERROR);
              paymentStatus.setText(
                  text(
                      "RemoteCompute.payment.pollRetry",
                      "The network is unstable. Payment status will be checked again; no new order will be created."));
              if (apiError != null && apiError.isUnauthorized()) {
                RemoteComputeConfig.invalidateZhiziToken();
                stopPolling();
                reauthenticationRequired = true;
                retryButton.setText(
                    text("RemoteCompute.payment.closeAndLogin", "Close and sign in again"));
                retryButton.setVisible(true);
              }
            } finally {
              pollWorker = null;
            }
          }
        };
    pollWorker.execute();
  }

  private void finishSuccess() {
    stopPolling();
    paymentStatus.setForeground(GREEN);
    paymentStatus.setText(
        text(
            "RemoteCompute.payment.confirmedVerifying",
            "Zhizi confirmed payment. Verifying the account update..."));
    retryButton.setVisible(false);
    verifySettlement();
  }

  private void verifySettlement() {
    if (settlementWorker != null || paymentBaseline == null || paymentFlow.order() == null) {
      return;
    }
    settlementWorker =
        new SwingWorker<ZhiziAccountService.PaymentVerification, Void>() {
          @Override
          protected ZhiziAccountService.PaymentVerification doInBackground() throws Exception {
            ZhiziAccountService.PaymentVerification latest = null;
            Exception lastFailure = null;
            for (int attempt = 0; attempt < SETTLEMENT_ATTEMPTS && !isCancelled(); attempt++) {
              try {
                latest =
                    accountService.verifyPayment(
                        accountToken, paymentBaseline, paymentFlow.order());
                lastFailure = null;
                if (latest.settled) {
                  return latest;
                }
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
              } catch (Exception failure) {
                lastFailure = failure;
              }
              if (attempt + 1 < SETTLEMENT_ATTEMPTS) {
                Thread.sleep(SETTLEMENT_RETRY_MILLIS);
              }
            }
            if (lastFailure != null && latest == null) {
              throw lastFailure;
            }
            return latest;
          }

          @Override
          protected void done() {
            try {
              if (isCancelled() || !isDisplayable()) {
                return;
              }
              ZhiziAccountService.PaymentVerification verification = get();
              if (verification != null && verification.settled) {
                paymentStatus.setForeground(GREEN);
                paymentStatus.setText(
                    paymentFlow.order().purpose == ZhiziApiClient.PaymentPurpose.BALANCE_TOP_UP
                        ? text(
                            "RemoteCompute.payment.topUpVerified",
                            "Top-up credited. Balance and cash record are up to date.")
                        : text(
                            "RemoteCompute.payment.vipVerified",
                            "VIP activated. Membership status is up to date."));
              } else {
                showSettlementPending();
              }
              notifyPaymentCompleted();
            } catch (Exception failure) {
              ZhiziApiException apiError = findApiError(failure);
              if (apiError != null && apiError.isUnauthorized()) {
                RemoteComputeConfig.invalidateZhiziToken();
              }
              showSettlementPending();
              notifyPaymentCompleted();
            } finally {
              settlementWorker = null;
            }
          }
        };
    settlementWorker.execute();
  }

  private void showSettlementPending() {
    paymentStatus.setForeground(ERROR);
    paymentStatus.setText(
        text(
            "RemoteCompute.payment.settlementPending",
            "Payment is confirmed, but account details have not synced yet. Refresh later and do not pay again."));
  }

  private void notifyPaymentCompleted() {
    accountService.clear();
    if (!completionNotified) {
      completionNotified = true;
      paymentCompleted.run();
    }
  }

  private void finishFailure() {
    stopPolling();
    paymentStatus.setForeground(ERROR);
    paymentStatus.setText(
        text(
            "RemoteCompute.payment.failed",
            "Zhizi reported that this order failed. You can create a new order."));
    retryButton.setText(text("RemoteCompute.payment.newOrder", "Create a new order"));
    retryButton.setVisible(true);
  }

  private void resetForNewOrder() {
    stopPolling();
    reauthenticationRequired = false;
    paymentFlow.reset();
    paymentBaseline = null;
    qrCode.setIcon(null);
    retryButton.setVisible(false);
    topUpStatus.setForeground(MUTED);
    topUpStatus.setText(
        text(
            "RemoteCompute.payment.topUpNotice",
            "The amount is credited to your Zhizi balance for on-demand compute."));
    productStatus.setForeground(MUTED);
    productStatus.setText(
        text(
            "RemoteCompute.payment.livePrice",
            "The price is loaded from Zhizi immediately before purchase."));
    setSelectionControlsEnabled(true);
    cardLayout.show(cards, CHOICE_CARD);
  }

  private PaymentChoice selectedChoice() {
    if (topUpModeButton.isSelected()) {
      TopUpItem selected = (TopUpItem) topUpBox.getSelectedItem();
      return selected == null ? null : PaymentChoice.topUp(selected.amountFen);
    }
    ProductItem selected = (ProductItem) productBox.getSelectedItem();
    return selected == null ? null : PaymentChoice.vip(selected.product);
  }

  private void setSelectionControlsEnabled(boolean enabled) {
    boolean selecting = enabled && paymentFlow.isSelecting();
    topUpModeButton.setEnabled(selecting);
    vipModeButton.setEnabled(selecting);
    topUpBox.setEnabled(selecting && topUpBox.getItemCount() > 0);
    productBox.setEnabled(selecting && productBox.getItemCount() > 0);
    updateCreateButtonState();
  }

  private void updateCreateButtonState() {
    createOrderButton.setEnabled(paymentFlow.isSelecting() && selectedChoice() != null);
  }

  private static void updateModeStyle(JToggleButton button, boolean selected) {
    button.setBackground(selected ? new Color(231, 245, 237) : CARD);
    button.setForeground(selected ? GREEN : TEXT);
    button.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(selected ? GREEN : BORDER),
            new EmptyBorder(9, 18, 9, 18)));
  }

  private void stopPolling() {
    if (pollTimer != null) {
      pollTimer.stop();
      pollTimer = null;
    }
    if (pollWorker != null) {
      pollWorker.cancel(true);
      pollWorker = null;
    }
  }

  @Override
  public void dispose() {
    stopPolling();
    if (paymentFlow.state() == PaymentFlow.State.SUCCESS) {
      notifyPaymentCompleted();
    }
    paymentFlow.close();
    if (productsWorker != null) {
      productsWorker.cancel(true);
      productsWorker = null;
    }
    if (createWorker != null) {
      createWorker.cancel(true);
      createWorker = null;
    }
    if (settlementWorker != null) {
      settlementWorker.cancel(true);
      settlementWorker = null;
    }
    super.dispose();
  }

  static BufferedImage renderQr(String value, int size) throws Exception {
    BitMatrix matrix = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size);
    return MatrixToImageWriter.toBufferedImage(matrix);
  }

  static String formatFen(long amountFen) {
    BigDecimal yuan = BigDecimal.valueOf(Math.max(0L, amountFen), 2);
    return "¥" + yuan.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
  }

  private static String monthsText(int months) {
    return format("RemoteCompute.payment.months", "{0}-month VIP", months);
  }

  private static ZhiziApiException findApiError(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof ZhiziApiException) {
        return (ZhiziApiException) current;
      }
      current = current.getCause();
    }
    return null;
  }

  private static JPanel cardPanel() {
    JPanel panel = new JPanel();
    panel.setBackground(CARD);
    panel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER), new EmptyBorder(24, 26, 24, 26)));
    return panel;
  }

  private static GridBagConstraints constraints() {
    GridBagConstraints gbc = new GridBagConstraints();
    gbc.gridx = 0;
    gbc.gridy = 0;
    gbc.weightx = 1;
    gbc.fill = GridBagConstraints.HORIZONTAL;
    gbc.anchor = GridBagConstraints.NORTHWEST;
    return gbc;
  }

  private static void stylePrimary(JButton button) {
    button.setPreferredSize(new Dimension(220, 44));
  }

  private static void styleSecondary(JButton button) {
    button.setPreferredSize(
        new Dimension(AccessibilitySupport.localizedControlWidth(button, 92), 42));
  }

  private static JButton primaryButton(String label) {
    return new RemoteComputeDialog.RoundedButton(
        label, GREEN, new Color(34, 121, 77), Color.WHITE, 18);
  }

  private static JButton secondaryButton(String label) {
    return new RemoteComputeDialog.RoundedButton(label, CARD, BORDER, TEXT, 16);
  }

  private static JToggleButton modeButton(String label) {
    JToggleButton button = new JToggleButton(label);
    button.setFocusPainted(false);
    button.setOpaque(true);
    button.setContentAreaFilled(true);
    button.setFont(button.getFont().deriveFont(Font.BOLD, 14F));
    button.setPreferredSize(new Dimension(220, 44));
    updateModeStyle(button, false);
    AccessibilitySupport.button(button, label, label);
    return button;
  }

  private static String text(String key, String fallback) {
    try {
      return Lizzie.resourceBundle == null ? fallback : Lizzie.resourceBundle.getString(key);
    } catch (MissingResourceException ignored) {
      return fallback;
    }
  }

  private static String format(String key, String fallback, Object... args) {
    return MessageFormat.format(text(key, fallback), args);
  }

  private static final class TopUpItem {
    private final long amountFen;

    private TopUpItem(long amountFen) {
      this.amountFen = amountFen;
    }

    @Override
    public String toString() {
      return formatFen(amountFen);
    }
  }

  private static final class PreparedOrder {
    private final ZhiziApiClient.PaymentOrder order;
    private final ZhiziAccountService.PaymentBaseline baseline;

    private PreparedOrder(
        ZhiziApiClient.PaymentOrder order, ZhiziAccountService.PaymentBaseline baseline) {
      this.order = order;
      this.baseline = baseline;
    }
  }

  static final class PaymentChoice {
    private final ZhiziApiClient.PaymentPurpose purpose;
    private final long amountFen;
    private final ZhiziApiClient.MembershipProduct product;
    private final String displayName;

    private PaymentChoice(
        ZhiziApiClient.PaymentPurpose purpose,
        long amountFen,
        ZhiziApiClient.MembershipProduct product,
        String displayName) {
      this.purpose = purpose;
      this.amountFen = amountFen;
      this.product = product;
      this.displayName = displayName;
    }

    static PaymentChoice topUp(long amountFen) {
      return new PaymentChoice(
          ZhiziApiClient.PaymentPurpose.BALANCE_TOP_UP,
          amountFen,
          null,
          text("RemoteCompute.payment.balanceTopUp", "Account balance top-up"));
    }

    static PaymentChoice vip(ZhiziApiClient.MembershipProduct product) {
      return new PaymentChoice(
          ZhiziApiClient.PaymentPurpose.VIP_MEMBERSHIP,
          product.priceFen,
          product,
          monthsText(product.durationMonths));
    }

    boolean isTopUp() {
      return purpose == ZhiziApiClient.PaymentPurpose.BALANCE_TOP_UP;
    }

    String confirmationLine() {
      return isTopUp()
          ? text("RemoteCompute.payment.confirmTopUp", "Purpose: Account balance top-up")
          : format("RemoteCompute.payment.confirmProduct", "Plan: {0}", displayName);
    }

    boolean matches(ZhiziApiClient.PaymentOrder order) {
      if (order == null || order.amountFen != amountFen || order.purpose != purpose) {
        return false;
      }
      String expectedProduct = product == null ? "" : product.name;
      return expectedProduct.equals(order.productName);
    }
  }

  static final class PaymentFlow {
    enum State {
      SELECTING,
      CREATING,
      PENDING,
      SUCCESS,
      FAILED,
      CLOSED
    }

    private State state = State.SELECTING;
    private PaymentChoice choice;
    private ZhiziApiClient.PaymentOrder order;

    synchronized boolean beginCreate(PaymentChoice selected) {
      if (state != State.SELECTING || selected == null) {
        return false;
      }
      choice = selected;
      order = null;
      state = State.CREATING;
      return true;
    }

    synchronized void orderCreated(ZhiziApiClient.PaymentOrder created) throws ZhiziApiException {
      if (state != State.CREATING || choice == null || !choice.matches(created)) {
        throw invalidOrder(ZhiziApiException.Operation.CREATE_ORDER);
      }
      order = created;
      state = stateFor(created.status);
    }

    synchronized void orderUpdated(ZhiziApiClient.PaymentOrder updated) throws ZhiziApiException {
      if (state != State.PENDING
          || order == null
          || updated == null
          || !order.id.equals(updated.id)
          || choice == null
          || !choice.matches(updated)) {
        throw invalidOrder(ZhiziApiException.Operation.FETCH_ORDER);
      }
      order = updated;
      state = stateFor(updated.status);
    }

    synchronized void createFailed() {
      if (state == State.CREATING) {
        state = State.SELECTING;
        choice = null;
        order = null;
      }
    }

    synchronized void reset() {
      if (state != State.CLOSED) {
        state = State.SELECTING;
        choice = null;
        order = null;
      }
    }

    synchronized void close() {
      state = State.CLOSED;
      choice = null;
      order = null;
    }

    synchronized boolean isSelecting() {
      return state == State.SELECTING;
    }

    synchronized boolean isPending() {
      return state == State.PENDING;
    }

    synchronized State state() {
      return state;
    }

    synchronized ZhiziApiClient.PaymentOrder order() {
      return order;
    }

    private static State stateFor(ZhiziApiClient.PaymentStatus status) {
      if (status == ZhiziApiClient.PaymentStatus.SUCCESS) {
        return State.SUCCESS;
      }
      if (status == ZhiziApiClient.PaymentStatus.FAIL) {
        return State.FAILED;
      }
      return State.PENDING;
    }

    private static ZhiziApiException invalidOrder(ZhiziApiException.Operation operation) {
      return new ZhiziApiException(200, "invalid_response", "", 0, false, operation);
    }
  }

  private static final class ProductItem {
    private final ZhiziApiClient.MembershipProduct product;

    private ProductItem(ZhiziApiClient.MembershipProduct product) {
      this.product = product;
    }

    @Override
    public String toString() {
      return monthsText(product.durationMonths) + " · " + formatFen(product.priceFen);
    }
  }

  private static final class TopUpRenderer extends DefaultListCellRenderer {
    @Override
    public Component getListCellRendererComponent(
        JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
      JLabel label =
          (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
      label.setBorder(new EmptyBorder(7, 12, 7, 12));
      label.setFont(label.getFont().deriveFont(Font.BOLD, 14F));
      return label;
    }
  }

  private static final class ProductRenderer extends DefaultListCellRenderer {
    @Override
    public Component getListCellRendererComponent(
        JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
      JLabel label =
          (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
      label.setBorder(new EmptyBorder(7, 12, 7, 12));
      label.setFont(label.getFont().deriveFont(Font.BOLD, 14F));
      return label;
    }
  }
}
