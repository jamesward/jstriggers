@(triggerSobjects: Set[String])var express = require("express");
var xmlparser = require("express-xml-bodyparser");

@triggerSobjects.map { name =>
  var @name = require("./@name");
}

var app = express();

app.set("port", (process.env.PORT || 5000));

app.use(xmlparser());

@triggerSobjects.map { name =>

  app.post("/@name", function(req, res) {

    var notification = req.body["soapenv:envelope"]["soapenv:body"][0]["notifications"][0];  var sessionId = notification["sessionid"][0];

    var data = {};

    var sobject = notification["notification"][0]["sobject"][0];
    Object.keys(sobject).forEach(function(key) {
      if (key.indexOf("sf:") == 0) {
        var newKey = key.substr(3);
        data[newKey] = sobject[key][0];
      }
    });

    @{name}.insertOrUpdate(data, sessionId);

    res.status(200).end();
  });

}

app.listen(app.get("port"), function() {
  console.log("Node app is running at localhost:" + app.get("port"));
});