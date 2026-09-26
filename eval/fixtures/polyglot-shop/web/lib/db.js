const { Pool } = require("pg");

const pool = new Pool({ connectionString: process.env.DATABASE_URL });

exports.query = (text, params) => pool.query(text, params);
