var oauthWindow;

var authorizeUri = "https://localhost:8443/AuthenticationServer/oauth/authorize";
var redirectUri = "POST_MESSAGE";
var tokenUri = "https://localhost:8443/AuthenticationServer/oauth/tokenJson";

$(document).ready(function () {
  $("[name='spl-ctrl-token-button']").button()
    .click(function (event) {
    event.preventDefault();

    var clientId = $("[name='clientId']").val();
    var oauthUri = authorizeUri + "?redirect_uri=" + redirectUri + "&resposne_type=code" + "&client_id=" + clientId;

    oauthWindow = window.open(oauthUri, "_blank", "height=400,width=350");
  });

  window.addEventListener('message', function (event) {
    oauthWindow.close();

    var clientId = $("[name='clientId']").val();
    var clientSecret = $("[name='clientSecret']").val();

    $.ajax({
      type: "POST",
      url: "https://localhost:8443/AuthenticationServer/oauth/tokenJson",
      data: {
        "client_id": clientId,
        "client_secret": clientSecret,
        "code": event.data.code,
        "redirect_uri": redirectUri
      },
      dataType: "jsonp",
      jsonp: "callback",
      jsonpCallback: "saveAccessToken"
    });
  });

});

function saveAccessToken(data) {
  $("[name='token']").val(data.access_token);
  $("[name='refreshToken']").val(data.refresh_token);
  $("[name='expirationDate']").val(data.expires_in + (new Date()).getTime());

}