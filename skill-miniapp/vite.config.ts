import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
    plugins: [react()],
    server: {
        port: 3001,
        open: true,
        proxy: {
            // Proxy REST API requests to Skill Server
            '/api': {
                target: 'http://localhost:8082',
                changeOrigin: true,
            },
            // Proxy WebSocket connections to Skill Server
            '/ws': {
                target: 'ws://localhost:8082',
                ws: true,
                changeOrigin: true,
            },
        },
    },
});
