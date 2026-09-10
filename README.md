# Order Inventory System

A modular monolith application built using Spring Boot, React, and Supabase PostgreSQL.

The system contains two backend modules:

- Order Module: `edu.cit.garciano.shop`
- Inventory Module: `edu.cit.garciano.inventory`

The Order and Inventory modules communicate in-process inside one Spring Boot application. The React frontend communicates with the backend using HTTP REST, while Spring Boot connects to Supabase PostgreSQL using Spring Data JPA.

---

## Technologies Used

- Java 19
- Spring Boot 4.1.1
- Spring Data JPA
- PostgreSQL
- Supabase
- React
- Vite
- Maven

---

## Project Structure

```text
Modular Monolith
├── backend
│   └── src/main/java/edu/cit/garciano
│       ├── shop
│       └── inventory
├── frontend
├── evidence
│   ├── confirmed.png
│   └── rejected.png
├── sql
│   └── schema.sql
├── .gitignore
└── README.md
```

---

## System Architecture

```text
React Frontend
      |
      | HTTP / REST
      v
Spring Boot Backend
      |
      +--------------------------+
      |                          |
 Order Module             Inventory Module
edu.cit.garciano.shop     edu.cit.garciano.inventory
      |                          |
      |     InventoryService     |
      +------------------------->|
                                 |
                                 v
                         Supabase PostgreSQL
```

The Order module depends only on the `InventoryService` interface.

`InventoryServiceImpl` is package-private, which prevents the Order module from directly depending on the Inventory implementation.

---

## Features

The system allows the user to:

- Select a product
- Enter an order quantity
- Submit an order
- Check available inventory
- Reduce stock when inventory is sufficient
- Return `CONFIRMED` for successful orders
- Return `REJECTED` when stock is insufficient
- Save both confirmed and rejected orders in Supabase
- Display updated inventory information in React

---

## Supabase Database Setup

Create a free Supabase project.

Open the Supabase SQL Editor and run the script found in:

```text
sql/schema.sql
```

The script creates two tables:

```text
inventory
orders
```

The inventory table is seeded with:

| Product ID | Product | Stock |
|---|---|---:|
| P100 | Wireless Mouse | 25 |
| P200 | Mechanical Keyboard | 10 |
| P300 | USB-C Hub | 0 |

For the Spring Boot connection, open the Supabase **Connect** menu and select:

```text
Connection Method: Session Pooler
Type: JDBC
Port: 5432
```

The database credentials are stored as environment variables and are not committed to GitHub.

Example:

```powershell
$env:SUPABASE_DB_URL="jdbc:postgresql://YOUR_HOST:5432/postgres?sslmode=require"
$env:SUPABASE_DB_USERNAME="YOUR_USERNAME"
$env:SUPABASE_DB_PASSWORD="YOUR_PASSWORD"
```

---

## Running the Backend

Open a terminal and go to:

```powershell
cd backend
```

Set the Supabase environment variables first, then run:

```powershell
.\mvnw spring-boot:run
```

The backend runs at:

```text
http://localhost:8080
```

---

## Running the Frontend

Open another terminal:

```powershell
cd frontend
```

Install dependencies:

```powershell
npm install
```

Run the React application:

```powershell
npm run dev
```

The frontend runs at:

```text
http://localhost:5173
```

---

## REST API

### Place Order

Endpoint:

```text
POST /api/orders
```

Full local URL:

```text
http://localhost:8080/api/orders
```

Example request:

```json
{
  "productId": "P100",
  "quantity": 1
}
```

Example successful response:

```json
{
  "status": "CONFIRMED",
  "reason": "Stock reserved successfully",
  "inventory": {
    "productId": "P100",
    "name": "Wireless Mouse",
    "stock": 24
  }
}
```

Example rejected response:

```json
{
  "status": "REJECTED",
  "reason": "Insufficient stock. Available stock: 0",
  "inventory": {
    "productId": "P300",
    "name": "USB-C Hub",
    "stock": 0
  }
}
```

---

## Network Tab Evidence

### Confirmed Order

The following screenshot shows a successful order request where enough inventory was available.

![Confirmed Order](evidence/confirmed.png)

### Rejected Order

The following screenshot shows a rejected order because the requested product had insufficient stock.

![Rejected Order](evidence/rejected.png)

---

# Reflection

Integrating the Order and Inventory modules in-process is simpler than running them as separate microservices. In this project, both modules are inside the same Spring Boot application, so `OrderService` can call `InventoryService` using a normal Java method call. There is no network communication between them. Because of this, communication is fast, debugging is easier, deployment is simpler, and both operations can participate in the same database transaction. If Order and Inventory were separated into different microservices, these advantages would no longer be available automatically. The system would need HTTP communication or messaging, JSON serialization, timeout handling, retries, authentication, monitoring, logging, and ways to handle cases where one service becomes unavailable.

Package-private visibility on `InventoryServiceImpl` is important because it helps enforce the boundary between the Inventory and Order modules. The Order module should depend only on the public `InventoryService` interface. Since `InventoryServiceImpl` is package-private, classes outside `edu.cit.garciano.inventory` cannot directly import and use it. This reduces tight coupling between modules. If `InventoryServiceImpl` were public, another developer could bypass the interface and directly depend on the implementation. That would make the system harder to maintain because changes to the Inventory implementation could also require changes to the Order module.

I would extract Inventory into its own microservice if the Inventory module became much larger, required independent deployment, needed separate scaling, or was used by several other applications. If Inventory became a separate service, the current direct call to `inventoryService.reserve()` would have to be replaced with network communication. The Order service could use Spring `RestClient`, `WebClient`, or another HTTP client to send requests to the Inventory service. The application would also need to handle network errors, timeouts, retries, authentication, service availability, and consistency between orders and inventory.