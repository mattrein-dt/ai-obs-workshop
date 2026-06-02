import { NextRequest } from 'next/server';

const API_URL = process.env.API_URL || 'http://request-orchestrator:8080';

export async function POST(req: NextRequest) {
  const body = await req.json();

  const response = await fetch(`${API_URL}/api/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });

  // Stream the response back
  return new Response(response.body, {
    headers: {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    },
  });
}
