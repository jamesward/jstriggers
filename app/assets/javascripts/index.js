window.name = "NG_DEFER_BOOTSTRAP!";

require([
  "angular",
  "angular-local-storage",
  "angular-ui-ace",
  "ui-bootstrap-tpls",
  "css!bootstrap-css",
  "css!app-css"
], function(angular) {
  angular.module("myApp", ["ui.ace", "ui.bootstrap", "LocalStorageModule"]).controller("AppController", function($scope, $http, localStorageService) {

    if (localStorageService.get("forceAuth") !== null) {
      $scope.forceAuth = localStorageService.get("forceAuth");
    }

    if ((window.serverModel["X-FORCE-ID-URL"] !== null) &&
        (window.serverModel["X-FORCE-ACCESS-TOKEN"] !== null) &&
        (window.serverModel["X-FORCE-REFRESH-TOKEN"] !== null) &&
        (window.serverModel["X-FORCE-INSTANCE-URL"] !== null)) {
      $scope.forceAuth = {
        idUrl: window.serverModel["X-FORCE-ID-URL"],
        accessToken: window.serverModel["X-FORCE-ACCESS-TOKEN"],
        refreshToken: window.serverModel["X-FORCE-REFRESH-TOKEN"],
        instanceUrl: window.serverModel["X-FORCE-INSTANCE-URL"]
      };
      localStorageService.set("forceAuth", $scope.forceAuth);
    }

    if (localStorageService.get("herokuAuth") !== null) {
      $scope.herokuAuth = localStorageService.get("herokuAuth");
    }

    if (window.serverModel["X-HEROKU-ACCESS-TOKEN"] !== null) {
      $scope.herokuAuth = {
        accessToken: window.serverModel["X-HEROKU-ACCESS-TOKEN"]
      };
      localStorageService.set("herokuAuth", $scope.herokuAuth);
    }

    $scope.logout = function() {
      localStorageService.clearAll();
      $scope.forceAuth = undefined;
      $scope.herokuAuth = undefined;
    };

    $scope.saveTrigger = function() {
      // todo: validate JSON

      $http
        .post("/trigger/" + $scope.selectedSObject.name, { deps: JSON.parse($scope.deps), trigger: $scope.selectedTrigger } )
        .success(function(data) {
          console.log(data);
        })
        .error(function(error) {
          console.error(error);
        });
    };

    $scope.$watch("selectedSObject", function(newValue) {
      if (newValue !== undefined) {
        $http
          .get("/trigger/" + newValue.name)
          .success(function(data) {
            $scope.selectedTrigger = data;
          })
          .error(function(error) {
            console.error(error);
          });
      }
    });

    if (($scope.forceAuth !== undefined) && ($scope.herokuAuth !== undefined)) {

      $http.defaults.headers.common["X-FORCE-ID-URL"] = $scope.forceAuth.idUrl;
      $http.defaults.headers.common["X-FORCE-ACCESS-TOKEN"] = $scope.forceAuth.accessToken;
      $http.defaults.headers.common["X-FORCE-REFRESH-TOKEN"] = $scope.forceAuth.refreshToken;
      $http.defaults.headers.common["X-FORCE-INSTANCE-URL"] = $scope.forceAuth.instanceUrl;
      $http.defaults.headers.common["X-HEROKU-ACCESS-TOKEN"] = $scope.herokuAuth.accessToken;

      $http
        .get("/sobjects")
        .success(function(data) {
          $scope.sobjects = data;
        })
        .error(function(error) {
          console.error(error);
        });

      $http
        .get("/deps")
        .success(function(data) {
          $scope.deps = JSON.stringify(data);
        })
        .error(function(error) {
          console.error(error);
        });

    }

  });

  angular.element(document).ready(function() {
    angular.resumeBootstrap();
  });

});