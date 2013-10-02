(function () {

    'use strict'

    angular.module('page', ['ngResource'])

        .controller('PageController', [
        '$scope',
        '$rootScope',
        '$resource',
        '$http',
        '$location',
        'entityMixins',

        function ($scope, $rootScope, $resource, $http, $location, entityMixins) {

            entityMixins.extendAll($scope, "page");

            $scope.publishPage = function () {
                $scope.page.published = true;
                $scope.updatePage();
            }

            $scope.updatePage = function () {
                $scope.isSaving = true;
                if ($scope.isNew()) {
                    $http.post("/api/pages/", $scope.page)
                        .success(function (data, status, headers, config) {
                            $scope.isSaving = false;
                            var fragments = headers("location").split('/'),
                                slug = fragments[fragments.length - 1];
                            $rootScope.$broadcast('pages:refreshList');
                            $location.url("/pages/" + slug);
                        })
                        .error(function (data, status, headers, config) {
                            $scope.isSaving = false;
                            // TODO handle 409 conflict
                        });
                }
                else {
                    $scope.PageResource.save({ "slug":$scope.slug }, $scope.page, function () {
                        $scope.isSaving = false;
                        $rootScope.$broadcast('pages:refreshList');
                    });
                }
            };

            $scope.PageResource = $resource("/api/pages/:slug");

            $scope.isNew = function () {
                return $scope.slug == "_new";
            };

            $scope.newPage = function () {
                return {
                    slug:"",
                    title:"",
                    addons:[]
                };
            }

            // Initialize existing page or new page

            if (!$scope.isNew()) {
                $scope.page = $scope.PageResource.get({
                    "slug":$scope.slug,
                    "expand":["images"] }, function () {

                    $scope.initializeAddons();
                    $scope.initializeModels();
                    $scope.initializeLocalization();

                    if ($scope.page.published == null) {
                        // "null" does not seem to be evaluated properly in angular directives
                        // (like ng-show="something != null")
                        // Thus, we convert "null" published flag to undefined to be able to have that "high impedance"
                        // state in angular directives.
                        $scope.page.published = undefined;
                    }
                });
            }
            else {
                $scope.page = $scope.newPage();
                $scope.initializeAddons();
                $scope.initializeModels();
                $scope.initializeLocalization();
            }

            $scope.confirmDeletion = function () {
                $rootScope.$broadcast('page:confirmDelete');
            }

            $scope.deletePage = function () {
                $scope.PageResource.delete({
                    "slug":$scope.slug
                }, function () {
                    $rootScope.$broadcast('page:dismissConfirmDelete');
                    $rootScope.$broadcast('pages:refreshList');
                    $location.url("/contents");
                });
            }

        }]);

})();