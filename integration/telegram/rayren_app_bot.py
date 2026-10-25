"""Registered by the existing bot; no unsolicited messages or access grants."""
import asyncio
from urllib.parse import quote
from rayren_app_feed import FeedStore, rights


async def send_import(update, context):
    from telegram import InlineKeyboardButton, InlineKeyboardMarkup
    if not update.effective_user or not update.effective_chat or update.effective_chat.type != 'private':
        return
    try:
        main, _ = await asyncio.to_thread(rights, update.effective_user.id)
        if not main:
            await update.effective_message.reply_text('Нет действующей привязанной подписки. Открой «Моя подписка» или обратись в поддержку.')
            return
        token = await asyncio.to_thread(lambda: FeedStore().issue(update.effective_user.id))
    except Exception:
        await update.effective_message.reply_text('Автоимпорт временно недоступен. Твоя обычная ссылка не изменилась.')
        return
    # Fragment never reaches the landing page server or its access logs.
    url = 'https://rayren.icu/app/import/#key=' + quote(token, safe='')
    await update.effective_message.reply_text(
        'Тестовый автоимпорт в Rayren 0.1.7: основные серверы и БС, если он тебе доступен, загрузятся вместе.\n\n'
        'Нажми кнопку, затем «Открыть Rayren» и подтверди добавление. Личная ссылка действует 90 дней; не пересылай её другим.',
        reply_markup=InlineKeyboardMarkup([[InlineKeyboardButton('Открыть в Rayren · тест', url=url)]]))
