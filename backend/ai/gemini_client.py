"""
gemini_client.py
Streams a response from Google Gemini for a list of images + prompt.
Yields text chunks as they arrive.
"""

from __future__ import annotations

import asyncio
import base64
import logging
from typing import AsyncGenerator

import google.generativeai as genai
from google.api_core.exceptions import ResourceExhausted, ServiceUnavailable
from tenacity import (
    retry,
    retry_if_exception_type,
    stop_after_attempt,
    wait_exponential,
)

from config import settings

logger = logging.getLogger(__name__)

# Initialise SDK once at import time
genai.configure(api_key=settings.gemini_api_key)


def _build_content_parts(image_bytes_list: list[bytes], prompt: str) -> list:
    """Build the multi-modal parts list expected by the Gemini SDK."""
    parts: list = []
    for img_bytes in image_bytes_list:
        parts.append(
            {
                "inline_data": {
                    "mime_type": "image/jpeg",
                    "data": base64.b64encode(img_bytes).decode("utf-8"),
                }
            }
        )
    parts.append({"text": prompt})
    return parts


async def stream_gemini(
    image_bytes_list: list[bytes],
    prompt: str,
) -> AsyncGenerator[str, None]:
    """
    Yield text chunks from Gemini as they are produced.
    Raises on unrecoverable errors after retries.
    """
    model = genai.GenerativeModel(settings.gemini_model)
    parts = _build_content_parts(image_bytes_list, prompt)

    attempt = 0
    max_attempts = settings.ai_max_retries

    while attempt < max_attempts:
        attempt += 1
        try:
            # Gemini SDK streaming is synchronous at the iterator level;
            # we run it in a thread pool to avoid blocking the event loop.
            response = await asyncio.get_event_loop().run_in_executor(
                None,
                lambda: model.generate_content(
                    parts,
                    stream=True,
                    generation_config=genai.GenerationConfig(
                        temperature=0.4,
                        max_output_tokens=4096,
                    ),
                ),
            )

            async def _iter():
                for chunk in response:
                    if chunk.text:
                        yield chunk.text

            async for chunk_text in _iter():
                yield chunk_text

            return  # success – exit retry loop

        except ResourceExhausted as exc:
            logger.warning("Gemini rate-limited (attempt %d/%d): %s", attempt, max_attempts, exc)
            if attempt >= max_attempts:
                raise RuntimeError(
                    f"Gemini quota exhausted after {max_attempts} attempts: {exc}"
                ) from exc
            await asyncio.sleep(settings.ai_retry_wait_seconds * attempt)

        except ServiceUnavailable as exc:
            logger.warning("Gemini unavailable (attempt %d/%d): %s", attempt, max_attempts, exc)
            if attempt >= max_attempts:
                raise RuntimeError(
                    f"Gemini service unavailable after {max_attempts} attempts: {exc}"
                ) from exc
            await asyncio.sleep(settings.ai_retry_wait_seconds * attempt)

        except Exception as exc:
            logger.error("Gemini unexpected error: %s", exc)
            raise RuntimeError(f"Gemini error: {exc}") from exc
