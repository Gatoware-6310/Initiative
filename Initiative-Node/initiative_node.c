#define _DEFAULT_SOURCE

#include "mongoose.h"

#include <stdio.h>
#include <string.h>

#ifdef __linux__
#include <stdlib.h>
#include <unistd.h>
#endif

enum node_mode { WAITING, CONNECTED };

struct initiative_node {
  enum node_mode mode;
  struct mg_connection *websocket;
};

int has_python(void) {
#ifdef __linux__
  return system("command -v python3 >/dev/null 2>&1") == 0;
#else
  return 0;
#endif
}

static const char *node_status(void) { return "lookin good!!!!!"; }

static const char *node_capabilities(void) {
  // Add one declaration per action handled below, using the External format.
  // Example: return "led_on\nbrightness\tvalue:Integer:0:100\n";
  return "";
}

static void send_text(struct mg_connection *connection, const char *message) {
  mg_ws_send(connection, message, strlen(message), WEBSOCKET_OP_TEXT);
}

static void run_python(struct mg_connection *connection, const char *source,
                       size_t source_length) {
#ifdef __linux__
  char path[] = "/tmp/initiative-node-XXXXXX";
  char command[sizeof path + 16];
  int file;
  FILE *script;

  if (!has_python()) {
    send_text(connection, "PYTHON_UNSUPPORTED");
    return;
  }

  file = mkstemp(path);
  if (file == -1 || (script = fdopen(file, "w")) == NULL) {
    send_text(connection, "PYTHON_ERROR");
    return;
  }
  fwrite(source, 1, source_length, script);
  fclose(script);

  snprintf(command, sizeof command, "python3 %s", path);
  if (system(command) == 0) {
    send_text(connection, "PYTHON_OK");
  } else {
    send_text(connection, "PYTHON_ERROR");
  }
  unlink(path);
#else
  (void)source;
  (void)source_length;
  send_text(connection, "PYTHON_UNSUPPORTED");
#endif
}

static void handle_message(struct initiative_node *node,
                           struct mg_connection *connection,
                           const char *message, size_t message_length) {
  (void)node;

  if (message_length == strlen("STATUS") &&
      memcmp(message, "STATUS", message_length) == 0) {
    send_text(connection, node_status());
  } else if (message_length == strlen("CAPABILITIES") &&
             memcmp(message, "CAPABILITIES", message_length) == 0) {
    send_text(connection, node_capabilities());
  } else if (message_length >= strlen("PYTHON ") &&
             memcmp(message, "PYTHON ", strlen("PYTHON ")) == 0) {
    run_python(connection, message + strlen("PYTHON "),
               message_length - strlen("PYTHON "));
  } else {
    // Add device-specific actions here and declare each one in
    // node_capabilities().
    send_text(connection, "UNKNOWN_COMMAND");
  }
}

static void event_handler(struct mg_connection *connection, int event,
                          void *event_data) {
  struct initiative_node *node = connection->fn_data;

  if (event == MG_EV_HTTP_MSG) {
    struct mg_http_message *request = event_data;
    char url[256];

    if (mg_strcmp(request->method, mg_str("POST")) != 0 ||
        !mg_match(request->uri, mg_str("/initiative/connect"), NULL)) {
      mg_http_reply(connection, 404, "", "Not found\n");
      return;
    }
    if (node->mode == CONNECTED || node->websocket != NULL) {
      mg_http_reply(connection, 409, "", "Already connected\n");
      return;
    }
    if (request->body.len == 0 || request->body.len >= sizeof(url)) {
      mg_http_reply(connection, 400, "", "A WebSocket URL is required\n");
      return;
    }

    memcpy(url, request->body.buf, request->body.len);
    url[request->body.len] = '\0';
    node->websocket =
        mg_ws_connect(connection->mgr, url, event_handler, node, NULL);
    if (node->websocket == NULL) {
      mg_http_reply(connection, 502, "", "Could not connect\n");
      return;
    }
    mg_http_reply(connection, 202, "", "Connecting\n");
  } else if (event == MG_EV_WS_OPEN) {
    node->mode = CONNECTED;
    printf("Connected to Initiative Core\n");
  } else if (event == MG_EV_WS_MSG) {
    struct mg_ws_message *websocket_message = event_data;
    handle_message(node, connection, websocket_message->data.buf,
                   websocket_message->data.len);
  } else if (event == MG_EV_CLOSE && connection == node->websocket) {
    node->websocket = NULL;
    node->mode = WAITING;
    printf("Waiting for Initiative Core\n");
  }
}

int main(int argc, char *argv[]) {
  struct mg_mgr manager;
  struct initiative_node node = {WAITING, NULL};
  const char *port = argc > 1 ? argv[1] : "1233";
  char listen_url[64];

  mg_mgr_init(&manager);
  snprintf(listen_url, sizeof listen_url, "http://0.0.0.0:%s", port);
  if (mg_http_listen(&manager, listen_url, event_handler, &node) == NULL) {
    fprintf(stderr, "Could not listen on %s\n", listen_url);
    mg_mgr_free(&manager);
    return 1;
  }

  printf("Initiative Node waiting on %s\n", listen_url);
  for (;;)
    mg_mgr_poll(&manager, 1000);
}
