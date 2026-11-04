"""
firebase_auth.py
FastAPI dependency that verifies a Firebase ID token sent in the
Authorization header and returns the decoded claims.
"""

from __future__ import annotations

import logging
from typing import Annotated

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from firebase_admin import auth as firebase_auth_module

logger = logging.getLogger(__name__)

_bearer = HTTPBearer(auto_error=True)


async def verify_token(
    credentials: Annotated[HTTPAuthorizationCredentials, Depends(_bearer)],
) -> dict:
    """
    Verifies the Firebase ID token in the Bearer header.
    Returns the decoded token dict on success, raises HTTP 401 on failure.
    """
    token = credentials.credentials
    try:
        decoded = firebase_auth_module.verify_id_token(token)
        return decoded
    except firebase_auth_module.ExpiredIdTokenError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token has expired. Please sign in again.",
        )
    except firebase_auth_module.RevokedIdTokenError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token has been revoked. Please sign in again.",
        )
    except Exception as exc:
        logger.warning("Token verification failed: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid authentication token.",
        )
