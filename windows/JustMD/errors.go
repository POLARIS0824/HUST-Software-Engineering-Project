package main

import "errors"

// 领域错误。前端依据错误文本给出业务化提示（NFR-USE-04），不裸露技术调用栈。
var (
	ErrNoteNotFound   = errors.New("笔记不存在或已被删除")
	ErrTitleEmpty     = errors.New("标题不能为空")
	ErrTitleTooLong   = errors.New("标题过长，最多 250 个字节")
	ErrTitleInvalid   = errors.New("标题包含非法字符")
	ErrNoteConflict   = errors.New("同名笔记已存在")
	ErrPathOutside    = errors.New("路径越出知识库范围")
	ErrIndexCorrupted = errors.New("索引损坏，请重建索引")
	ErrSaveFailed     = errors.New("保存失败：无法写入文件，请检查磁盘空间与权限")
	ErrNoWorkspace    = errors.New("尚未选择知识库文件夹")
)
