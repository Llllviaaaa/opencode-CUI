import { parse as jsoncParse } from 'jsonc-parser';
import { promises as fs } from 'fs';

/**
 * JSONC parser that supports comments and trailing commas
 */
export class JsoncParser {
  /**
   * Parse a JSONC string into an object
   * @param content JSONC string content
   * @returns Parsed object
   * @throws Error if parsing fails
   */
  public parse(content: string): any {
    try {
      return jsoncParse(content);
    } catch (error) {
      throw new Error(`Failed to parse JSONC: ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  /**
   * Parse a JSONC file from the filesystem
   * @param filePath Path to the JSONC file
   * @returns Parsed object or null if file doesn't exist
   * @throws Error if parsing fails (but not if file doesn't exist)
   */
  public async parseFile(filePath: string): Promise<any> {
    try {
      const content = await fs.readFile(filePath, 'utf-8');
      return this.parse(content);
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT') {
        // File doesn't exist
        return null;
      }
      throw new Error(`Failed to read or parse JSONC file ${filePath}: ${(error as Error).message}`);
    }
  }
}