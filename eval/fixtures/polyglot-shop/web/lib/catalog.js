const db = require("./db");

exports.find = async (term) => {
  const { rows } = await db.query(
    "SELECT sku, name FROM products WHERE name ILIKE $1 LIMIT 50",
    ["%" + term + "%"]
  );
  return rows;
};
