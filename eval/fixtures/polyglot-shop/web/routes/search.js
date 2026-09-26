const catalog = require("../lib/catalog");

function renderList(items) {
  return "<ul>" + items.map((i) => `<li>${i.name}</li>`).join("") + "</ul>";
}

exports.results = async (req, res) => {
  const term = req.query.q || "";
  const items = await catalog.find(term);
  res.send("<h1>Results for " + term + "</h1>" + renderList(items));
};
