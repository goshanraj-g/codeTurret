const express = require("express");
const helmet = require("helmet");

const search = require("./routes/search");
const admin = require("./routes/admin");
const users = require("./routes/users");
const prefs = require("./routes/prefs");
const { safeRedirect, followNext } = require("./lib/redirect");

const app = express();
app.use(helmet());
app.use(express.json());
app.use(express.urlencoded({ extended: false }));

app.get("/search", search.results);
app.get("/users/:id", users.show);
app.post("/admin/backup", admin.requireAdmin, admin.backup);
app.post("/prefs", prefs.update);
app.get("/continue", followNext);
app.get("/home", (req, res) => safeRedirect(res, "/"));

app.listen(process.env.PORT || 3000);
