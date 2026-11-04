"""
image_storage.py
Handles upload of image bytes to Firebase Storage and returns signed URLs.
"""

from __future__ import annotations

import io
import logging
import mimetypes
import uuid
from typing import Optional

from firebase_admin import storage as fb_storage
from config import settings

logger = logging.getLogger(__name__)


def _bucket():
    return fb_storage.bucket(settings.firebase_storage_bucket)


async def upload_image(
    image_bytes: bytes,
    session_id: str,
    batch_id: str,
    device_id: str,
    filename: Optional[str] = None,
    content_type: str = "image/jpeg",
) -> tuple[str, str]:
    """
    Upload *image_bytes* to Firebase Storage.

    Returns:
        (storage_path, download_url)
    """
    photo_id = filename or str(uuid.uuid4())
    storage_path = f"sessions/{session_id}/batches/{batch_id}/{device_id}/{photo_id}.jpg"

    bucket = _bucket()
    blob = bucket.blob(storage_path)
    blob.upload_from_file(
        io.BytesIO(image_bytes),
        content_type=content_type,
    )

    # Make the blob publicly readable (or use signed URLs for private buckets)
    blob.make_public()
    download_url: str = blob.public_url

    logger.info("Uploaded image to %s", storage_path)
    return storage_path, download_url


async def delete_image(storage_path: str) -> None:
    """Remove a blob from Firebase Storage."""
    bucket = _bucket()
    blob = bucket.blob(storage_path)
    try:
        blob.delete()
        logger.info("Deleted image at %s", storage_path)
    except Exception as exc:
        logger.warning("Could not delete %s: %s", storage_path, exc)


async def download_image(storage_path: str) -> bytes:
    """Download image bytes from Firebase Storage."""
    bucket = _bucket()
    blob = bucket.blob(storage_path)
    return blob.download_as_bytes()
