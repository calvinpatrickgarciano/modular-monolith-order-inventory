import { useState } from "react";
import "./App.css";

function App() {
  const [productId, setProductId] = useState("P100");
  const [quantity, setQuantity] = useState(1);
  const [result, setResult] = useState(null);
  const [loading, setLoading] = useState(false);

  const placeOrder = async (event) => {
    event.preventDefault();

    setLoading(true);
    setResult(null);

    try {
      const response = await fetch("http://localhost:8080/api/orders", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          productId: productId,
          quantity: Number(quantity),
        }),
      });

      const data = await response.json();
      setResult(data);
    } catch (error) {
      setResult({
        status: "ERROR",
        reason: "Could not connect to the Spring Boot backend.",
      });
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="page">
      <div className="card">
        <div className="header">
          <h1>Order Inventory System</h1>
          <p>Place an order and check inventory availability instantly.</p>
        </div>

        <form onSubmit={placeOrder} className="order-form">
          <div className="form-group">
            <label htmlFor="product">Product</label>
            <select
              id="product"
              value={productId}
              onChange={(e) => setProductId(e.target.value)}
            >
              <option value="P100">P100 - Wireless Mouse</option>
              <option value="P200">P200 - Mechanical Keyboard</option>
              <option value="P300">P300 - USB-C Hub</option>
            </select>
          </div>

          <div className="form-group">
            <label htmlFor="quantity">Quantity</label>
            <input
              id="quantity"
              type="number"
              min="1"
              value={quantity}
              onChange={(e) => setQuantity(e.target.value)}
            />
          </div>

          <button type="submit" disabled={loading}>
            {loading ? "Processing..." : "Place Order"}
          </button>
        </form>

        {result && (
          <div
            className={`result-box ${
              result.status === "CONFIRMED"
                ? "success"
                : result.status === "REJECTED"
                ? "rejected"
                : "error"
            }`}
          >
            <div className="result-header">
              <h2>{result.status}</h2>
            </div>

            <p><strong>Reason:</strong> {result.reason}</p>

            {result.inventory && (
              <div className="result-details">
                <p><strong>Product ID:</strong> {result.inventory.productId}</p>
                <p><strong>Product:</strong> {result.inventory.name}</p>
                <p><strong>Remaining Stock:</strong> {result.inventory.stock}</p>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

export default App;