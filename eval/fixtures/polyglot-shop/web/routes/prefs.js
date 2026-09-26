const { deepMerge } = require("../lib/merge");

const defaults = { theme: "light", currency: "USD", emails: { news: false } };

exports.update = (req, res) => {
  const prefs = deepMerge({}, defaults);
  deepMerge(prefs, req.body);
  res.json(prefs);
};
