import { createApp } from "./app.js";
import { browserManager } from "./services/browser.js";

const app = createApp();
const PORT = 3000;

browserManager.registerSignalHandlers();

app.listen(PORT, () => {
  console.log(`Server running on port ${PORT}`);
});
