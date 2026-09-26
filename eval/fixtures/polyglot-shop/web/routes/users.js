const db = require("../lib/db");

exports.show = async (req, res) => {
  const { rows } = await db.query(
    "SELECT id, display_name, created_at FROM users WHERE id = $1",
    [req.params.id]
  );
  if (rows.length === 0) return res.status(404).end();
  res.json(rows[0]);
};
