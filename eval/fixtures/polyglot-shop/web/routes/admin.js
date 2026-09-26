const { exec } = require("child_process");

exports.requireAdmin = (req, res, next) => {
  if (req.user && req.user.role === "admin") return next();
  res.status(403).end();
};

exports.backup = (req, res) => {
  const label = req.body.label;
  exec("tar czf /var/backups/shop-" + label + ".tgz /srv/shop/data", (err) => {
    if (err) return res.status(500).json({ ok: false });
    res.json({ ok: true });
  });
};
