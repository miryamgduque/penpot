/**
 * Legacy all-in-one entry (manual install via manifest.json): one panel
 * with every tab. The native Penpot build uses the split entries
 * plugin-chat.ts / plugin-skills.ts instead.
 */
import { openPanel } from "./plugin";

openPanel("Penpot Skills", "all");
