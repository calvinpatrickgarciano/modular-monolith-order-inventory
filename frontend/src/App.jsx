import { useEffect, useMemo, useRef, useState } from "react";
import "./App.css";

const API = "http://localhost:8080/api";
const LOW_STOCK_THRESHOLD = 5;

function App() {
  const [inventory, setInventory] = useState([]);
  const [orders, setOrders] = useState([]);
  const [notifications, setNotifications] = useState([]);

  const [selectedProduct, setSelectedProduct] = useState("");
  const [cart, setCart] = useState([]);

  const [result, setResult] = useState(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const [notificationOpen, setNotificationOpen] = useState(false);
  const [lastSeenNotificationId, setLastSeenNotificationId] = useState(null);

  const notificationRef = useRef(null);

  // =========================
  // LOAD DASHBOARD DATA
  // =========================

  const loadDashboard = async () => {
    try {
      const [inventoryResponse, ordersResponse, notificationsResponse] =
        await Promise.all([
          fetch(`${API}/inventory`),
          fetch(`${API}/orders`),
          fetch(`${API}/notifications`),
        ]);

      if (
        !inventoryResponse.ok ||
        !ordersResponse.ok ||
        !notificationsResponse.ok
      ) {
        throw new Error("Failed to load dashboard data.");
      }

      const inventoryData = await inventoryResponse.json();
      const ordersData = await ordersResponse.json();
      const notificationsData = await notificationsResponse.json();

      setInventory(inventoryData);
      setOrders(ordersData);
      setNotifications(notificationsData);

      if (!selectedProduct && inventoryData.length > 0) {
        setSelectedProduct(inventoryData[0].productId);
      }
    } catch (err) {
      console.error(err);
      setError("Unable to load dashboard data.");
    }
  };

  useEffect(() => {
    loadDashboard();
  }, []);

  // =========================
  // AUTO HIDE TOAST
  // =========================

  useEffect(() => {
    if (!result) return;

    const timer = setTimeout(() => {
      setResult(null);
    }, 4000);

    return () => clearTimeout(timer);
  }, [result]);

  // =========================
  // CLOSE NOTIFICATION DROPDOWN
  // WHEN CLICKING OUTSIDE
  // =========================

  useEffect(() => {
    const handleClickOutside = (event) => {
      if (
        notificationRef.current &&
        !notificationRef.current.contains(event.target)
      ) {
        setNotificationOpen(false);
      }
    };

    document.addEventListener("mousedown", handleClickOutside);

    return () => {
      document.removeEventListener("mousedown", handleClickOutside);
    };
  }, []);

  // =========================
  // CART
  // =========================

  const addToCart = () => {
    if (!selectedProduct) return;

    const product = inventory.find(
      (item) => item.productId === selectedProduct
    );

    if (!product) return;

    setCart((currentCart) => {
      const existing = currentCart.find(
        (item) => item.productId === selectedProduct
      );

      if (existing) {
        return currentCart.map((item) =>
          item.productId === selectedProduct
            ? {
                ...item,
                quantity: item.quantity + 1,
              }
            : item
        );
      }

      return [
        ...currentCart,
        {
          productId: product.productId,
          name: product.name,
          quantity: 1,
        },
      ];
    });
  };

  const updateQuantity = (productId, value) => {
    const quantity = Number(value);

    if (quantity < 1) return;

    setCart((currentCart) =>
      currentCart.map((item) =>
        item.productId === productId
          ? {
              ...item,
              quantity,
            }
          : item
      )
    );
  };

  const removeFromCart = (productId) => {
    setCart((currentCart) =>
      currentCart.filter((item) => item.productId !== productId)
    );
  };

  // =========================
  // SUBMIT ORDER
  // =========================

  const submitOrder = async () => {
    if (cart.length === 0) return;

    setLoading(true);
    setError("");
    setResult(null);

    try {
      const response = await fetch(`${API}/orders`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          items: cart.map((item) => ({
            productId: item.productId,
            quantity: item.quantity,
          })),
        }),
      });

      const data = await response.json();

      if (!response.ok) {
        throw new Error(data.message || "Unable to submit order.");
      }

      setResult({
        status: data.status,
        reason: data.reason,
      });

      if (data.status === "CONFIRMED") {
        setCart([]);
      }

      await loadDashboard();
    } catch (err) {
      console.error(err);
      setError(err.message || "Unable to submit order.");
    } finally {
      setLoading(false);
    }
  };

  // =========================
  // CANCEL ORDER
  // =========================

  const cancelOrder = async (orderId) => {
    const confirmed = window.confirm(
      `Cancel Order O${orderId} and return its items to inventory?`
    );

    if (!confirmed) return;

    setLoading(true);
    setError("");
    setResult(null);

    try {
      const response = await fetch(`${API}/orders/${orderId}/cancel`, {
        method: "POST",
      });

      let data = {};

      try {
        data = await response.json();
      } catch {
        data = {};
      }

      if (!response.ok) {
        if (response.status === 404) {
          throw new Error("Order does not exist.");
        }

        if (response.status === 409) {
          throw new Error(
            data.message ||
              data.reason ||
              "This order cannot be cancelled."
          );
        }

        throw new Error("Unable to cancel order.");
      }

      setResult({
        status: data.status,
        reason: data.reason,
      });

      await loadDashboard();
    } catch (err) {
      console.error(err);
      setError(err.message || "Unable to cancel order.");
    } finally {
      setLoading(false);
    }
  };

  // =========================
  // NOTIFICATION HELPERS
  // =========================

  const getNotificationInfo = (message = "") => {
  const text = message.toLowerCase();

  if (text.includes("confirmed")) {
    return {
      icon: "✓",
      title: "Order Confirmed",
      type: "success",
    };
  }

  if (
    text.includes("cancelled") ||
    text.includes("returned to inventory")
  ) {
    return {
      icon: "↩",
      title: "Order Cancelled",
      type: "cancelled",
    };
  }

  if (
    text.includes("rejected") ||
    text.includes("could not be completed")
  ) {
    return {
      icon: "!",
      title: "Order Rejected",
      type: "error",
    };
  }

  if (
    text.includes("low stock") ||
    text.includes("reorder")
  ) {
    return {
      icon: "!",
      title: "Low Stock Alert",
      type: "warning",
    };
  }

  return {
    icon: "i",
    title: "Notification",
    type: "info",
  };
};

  const unreadNotifications = useMemo(() => {
    if (notifications.length === 0) {
      return 0;
    }

    if (lastSeenNotificationId === null) {
      return notifications.length;
    }

    return notifications.filter(
      (notification) =>
        notification.notificationId > lastSeenNotificationId
    ).length;
  }, [notifications, lastSeenNotificationId]);

  const toggleNotifications = () => {
    const willOpen = !notificationOpen;

    setNotificationOpen(willOpen);

    if (willOpen && notifications.length > 0) {
      const latestId = Math.max(
        ...notifications.map(
          (notification) => notification.notificationId
        )
      );

      setLastSeenNotificationId(latestId);
    }
  };

  // =========================
  // DATE FORMAT
  // =========================

  const formatDate = (date) => {
    if (!date) return "";

    return new Date(date).toLocaleString();
  };

  // =========================
  // STATISTICS
  // =========================

  const lowStockCount = inventory.filter(
    (item) => item.stock < LOW_STOCK_THRESHOLD
  ).length;

  return (
    <div className="app">
      <div className="dashboard">
        {/* ================= HEADER ================= */}

        <header className="dashboard-header">
          <div>
            <div className="eyebrow">
              MODULAR MONOLITH • LAB 2
            </div>

            <h1>
              Order & Inventory{" "}
              <span>Dashboard</span>
            </h1>

            <p>
              Multi-item ordering, live inventory,
              cancellation and event-driven notifications.
            </p>
          </div>

          <div className="header-actions">
            {/* NOTIFICATION BELL */}

            <div
              className="notification-wrapper"
              ref={notificationRef}
            >
              <button
                className={`notification-bell ${
                  notificationOpen ? "active" : ""
                }`}
                onClick={toggleNotifications}
                aria-label="Notifications"
              >
                <svg
                  viewBox="0 0 24 24"
                  width="21"
                  height="21"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                >
                  <path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9" />
                  <path d="M13.73 21a2 2 0 0 1-3.46 0" />
                </svg>

                {unreadNotifications > 0 && (
                  <span className="notification-badge">
                    {unreadNotifications > 9
                      ? "9+"
                      : unreadNotifications}
                  </span>
                )}
              </button>

              {notificationOpen && (
                <div className="notification-dropdown">
                  <div className="notification-dropdown-header">
                    <div>
                      <strong>Notifications</strong>
                      <span>Recent activity</span>
                    </div>

                    <button
                      className="notification-close"
                      onClick={() =>
                        setNotificationOpen(false)
                      }
                    >
                      ×
                    </button>
                  </div>

                  <div className="notification-dropdown-list">
                    {notifications.length === 0 ? (
                      <div className="notification-empty">
                        <div className="empty-bell">🔔</div>
                        No notifications yet.
                      </div>
                    ) : (
                      notifications
                        .slice(0, 5)
                        .map((notification) => {
                          const info =
                            getNotificationInfo(
                              notification.message
                            );

                          return (
                            <div
                              className="notification-dropdown-item"
                              key={
                                notification.notificationId
                              }
                            >
                              <div
                                className={`dropdown-notification-icon ${info.type}`}
                              >
                                {info.icon}
                              </div>

                              <div className="dropdown-notification-content">
                                <strong>
                                  {info.title}
                                </strong>

                                <p>
                                  {
                                    notification.message
                                  }
                                </p>

                                <span>
                                  {formatDate(
                                    notification.createdAt
                                  )}
                                </span>
                              </div>
                            </div>
                          );
                        })
                    )}
                  </div>

                  {notifications.length > 0 && (
                    <div className="notification-dropdown-footer">
                      Latest{" "}
                      {Math.min(
                        notifications.length,
                        5
                      )}{" "}
                      notifications
                    </div>
                  )}
                </div>
              )}
            </div>

            <button
              className="refresh-button"
              onClick={loadDashboard}
            >
              <svg
                viewBox="0 0 24 24"
                width="17"
                height="17"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
              >
                <path d="M20 11a8.1 8.1 0 0 0-15.5-2M4 4v5h5" />
                <path d="M4 13a8.1 8.1 0 0 0 15.5 2M20 20v-5h-5" />
              </svg>

              Refresh
            </button>
          </div>
        </header>

        {/* ================= STATS ================= */}

        <section className="stats-grid">
          <div className="stat-card">
            <span>Products</span>
            <strong>{inventory.length}</strong>
          </div>

          <div className="stat-card">
            <span>Orders</span>
            <strong>{orders.length}</strong>
          </div>

          <div className="stat-card">
            <span>Low Stock</span>
            <strong className="warning-number">
              {lowStockCount}
            </strong>
          </div>

          <div className="stat-card">
            <span>Activity</span>
            <strong>{notifications.length}</strong>
          </div>
        </section>

        {/* ================= TOAST ================= */}

        {result && (
          <div
            className={`status-toast ${result.status.toLowerCase()}`}
          >
            <div className="status-toast-icon">
              {result.status === "CONFIRMED"
                ? "✓"
                : result.status === "CANCELLED"
                ? "↩"
                : "!"}
            </div>

            <div className="status-toast-content">
              <strong>
                {result.status === "CONFIRMED"
                  ? "Order Confirmed"
                  : result.status === "CANCELLED"
                  ? "Order Cancelled"
                  : "Order Rejected"}
              </strong>

              <span>{result.reason}</span>
            </div>

            <button
              className="toast-close"
              onClick={() => setResult(null)}
            >
              ×
            </button>
          </div>
        )}

        {error && (
          <div className="status-toast rejected">
            <div className="status-toast-icon">!</div>

            <div className="status-toast-content">
              <strong>Something went wrong</strong>
              <span>{error}</span>
            </div>

            <button
              className="toast-close"
              onClick={() => setError("")}
            >
              ×
            </button>
          </div>
        )}

        {/* ================= MAIN GRID ================= */}

        <main className="main-grid">
          {/* CART */}

          <section className="panel">
            <div className="section-label">ORDER</div>

            <h2>Build Your Cart</h2>

            <div className="product-selector">
              <select
                value={selectedProduct}
                onChange={(event) =>
                  setSelectedProduct(event.target.value)
                }
              >
                {inventory.map((product) => (
                  <option
                    key={product.productId}
                    value={product.productId}
                  >
                    {product.productId} - {product.name} (
                    {product.stock} available)
                  </option>
                ))}
              </select>

              <button
                className="primary-button add-button"
                onClick={addToCart}
              >
                + Add
              </button>
            </div>

            <div className="cart-list">
              {cart.length === 0 ? (
                <div className="empty-state">
                  Your cart is empty.
                </div>
              ) : (
                cart.map((item) => (
                  <div
                    className="cart-item"
                    key={item.productId}
                  >
                    <div>
                      <strong>{item.name}</strong>
                      <span>{item.productId}</span>
                    </div>

                    <div className="cart-item-actions">
                      <input
                        type="number"
                        min="1"
                        value={item.quantity}
                        onChange={(event) =>
                          updateQuantity(
                            item.productId,
                            event.target.value
                          )
                        }
                      />

                      <button
                        className="remove-button"
                        onClick={() =>
                          removeFromCart(
                            item.productId
                          )
                        }
                      >
                        ×
                      </button>
                    </div>
                  </div>
                ))
              )}
            </div>

            <button
              className="primary-button submit-button"
              disabled={
                cart.length === 0 || loading
              }
              onClick={submitOrder}
            >
              {loading
                ? "Processing..."
                : "Submit Multi-Item Order"}
            </button>
          </section>

          {/* INVENTORY */}

          <section className="panel">
            <div className="panel-heading">
              <div>
                <div className="section-label">
                  LIVE DATA
                </div>

                <h2>Inventory</h2>
              </div>

              <span className="small-muted">
                Low stock &lt;{" "}
                {LOW_STOCK_THRESHOLD}
              </span>
            </div>

            <div className="inventory-table">
              <div className="inventory-row inventory-header">
                <span>ID</span>
                <span>PRODUCT</span>
                <span>STOCK</span>
              </div>

              {inventory.map((product) => {
                const out = product.stock === 0;

                const low =
                  product.stock > 0 &&
                  product.stock <
                    LOW_STOCK_THRESHOLD;

                return (
                  <div
                    className={`inventory-row ${
                      out
                        ? "out-stock"
                        : low
                        ? "low-stock"
                        : ""
                    }`}
                    key={product.productId}
                  >
                    <span>{product.productId}</span>

                    <span>{product.name}</span>

                    <span>
                      <span
                        className={`stock-badge ${
                          out
                            ? "out"
                            : low
                            ? "low"
                            : "good"
                        }`}
                      >
                        {product.stock}

                        {out
                          ? " • OUT"
                          : low
                          ? " • LOW"
                          : ""}
                      </span>
                    </span>
                  </div>
                );
              })}
            </div>
          </section>

          {/* ORDERS */}

          <section className="panel history-panel">
            <div className="section-label">
              HISTORY
            </div>

            <h2>Orders</h2>

            <div className="order-list">
              {orders.length === 0 ? (
                <div className="empty-state">
                  No orders yet.
                </div>
              ) : (
                orders.map((order) => (
                  <div
                    className="order-card"
                    key={order.orderId}
                  >
                    <div className="order-top">
                      <div>
                        <strong>
                          Order O{order.orderId}
                        </strong>

                        <span>
                          {formatDate(
                            order.createdAt
                          )}
                        </span>
                      </div>

                      <span
                        className={`order-status ${order.status.toLowerCase()}`}
                      >
                        {order.status}
                      </span>
                    </div>

                    <div className="order-items">
                      {order.items?.map(
                        (item, index) => (
                          <span
                            key={`${order.orderId}-${item.productId}-${index}`}
                          >
                            {item.productId} ×{" "}
                            {item.quantity}
                          </span>
                        )
                      )}
                    </div>

                    <p className="order-reason">
                      {order.reason}
                    </p>

                    {order.status ===
                      "CONFIRMED" && (
                      <button
                        className="cancel-button"
                        onClick={() =>
                          cancelOrder(
                            order.orderId
                          )
                        }
                      >
                        Cancel & Restock
                      </button>
                    )}
                  </div>
                ))
              )}
            </div>
          </section>

          {/* ACTIVITY FEED */}

          <section className="panel activity-panel">
            <div className="section-label">
              EVENT STREAM
            </div>

            <h2>Activity Feed</h2>

            <div className="activity-list">
              {notifications.length === 0 ? (
                <div className="empty-state">
                  No activity yet.
                </div>
              ) : (
                notifications.map(
                  (notification) => {
                    const info =
                      getNotificationInfo(
                        notification.message
                      );

                    return (
                      <div
                        className="activity-item"
                        key={
                          notification.notificationId
                        }
                      >
                        <div
                          className={`activity-icon ${info.type}`}
                        >
                          {info.icon}
                        </div>

                        <div className="activity-content">
                          <strong>
                            {info.title}
                          </strong>

                          <p>
                            {
                              notification.message
                            }
                          </p>

                          <span>
                            {formatDate(
                              notification.createdAt
                            )}
                          </span>
                        </div>
                      </div>
                    );
                  }
                )
              )}
            </div>
          </section>
        </main>
      </div>
    </div>
  );
}

export default App;