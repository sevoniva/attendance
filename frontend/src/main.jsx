import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { MantineProvider } from "@mantine/core";
import "@mantine/core/styles.css";
import App from "./App";

createRoot(document.getElementById("root")).render(
  <StrictMode>
    <MantineProvider
      theme={{
        primaryColor: "blue",
        fontFamily:
          '"SF Pro Display", Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif',
        headings: {
          fontFamily:
            '"SF Pro Display", Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif',
        },
      }}
      defaultColorScheme="light"
    >
      <App />
    </MantineProvider>
  </StrictMode>
);
