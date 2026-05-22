// #ifdef H5
export const BASE_URL = `${location.protocol}//${location.host}/wxmapi`
// #endif
// #ifndef H5
// 请求接口
export const BASE_URL = 'https://www.wxmblog.com/wxmapi'; //正式接口  
//export const BASE_URL = 'http://192.168.31.121:8088'; //本地接口  
// #endif

// #ifdef MP-WEIXIN
export const AMAPKEY = '7528e756feaebfabd1abbb1b04097a1e'; //高德定位小程序
// #endif
// #ifdef APP-PLUS
export const AMAPKEY = 'cdd98f785c3d27a17bf4d7022783ede9'; //高德定位APP
// #endif

const DEFAULT_SUCCESS_CODES = [200]
const LOGIN_EXPIRED_CODES = [2]
let lastToastMessage = ''
let lastToastTime = 0

const isSameCode = (left, right) => String(left) === String(right)
const includesCode = (codes = [], code) => (Array.isArray(codes) ? codes : [codes]).some(item => isSameCode(item, code))
const getPayload = (response) => response && response.data ? response.data : {}
const getMessage = (response) => {
	const payload = getPayload(response)
	return payload.msg || payload.message || '请求失败'
}
const defineHiddenValue = (target, key, value) => {
	if (!target || typeof target !== 'object') {
		return
	}
	Object.defineProperty(target, key, {
		value,
		enumerable: false,
		configurable: true,
		writable: true
	})
}

const showToastOnce = (title, duration = 2000) => {
	const message = String(title || '请求失败')
	const now = Date.now()
	if (lastToastMessage === message && now - lastToastTime < duration) {
		return
	}
	lastToastMessage = message
	lastToastTime = now
	uni.showToast({
		icon: 'none',
		title: message,
		duration
	})
}

export const attachRequestMeta = (response, successCodes = DEFAULT_SUCCESS_CODES) => {
	if (!response || typeof response !== 'object') {
		return response
	}
	const payload = getPayload(response)
	const meta = {
		code: payload.code,
		msg: getMessage(response),
		data: payload.data,
		ok: includesCode(successCodes, payload.code)
	}
	defineHiddenValue(response, '__requestMeta', meta)
	response.ok = meta.ok
	response.bizCode = meta.code
	response.bizMsg = meta.msg
	response.bizData = meta.data
	return response
}

export const isRequestSuccess = (response, successCodes = DEFAULT_SUCCESS_CODES) => {
	const target = attachRequestMeta(response, successCodes)
	return !!(target && target.ok)
}

export const getRequestCode = (response) => {
	const target = attachRequestMeta(response)
	return target ? target.bizCode : undefined
}

export const getRequestMessage = (response) => {
	const target = attachRequestMeta(response)
	return target ? target.bizMsg : '请求失败'
}

export const hasHandledRequestError = (response) => !!(response && response.__requestErrorHandled)

export const markRequestErrorHandled = (response) => {
	if (response && typeof response === 'object') {
		defineHiddenValue(response, '__requestErrorHandled', true)
	}
	return response
}

export const handleRequestError = (response, options = {}) => {
	const {
		successCodes = DEFAULT_SUCCESS_CODES,
		silentCodes = [],
		onShow,
		showToast = true
	} = options
	const target = attachRequestMeta(response, successCodes)
	if (!target || target.ok || includesCode(silentCodes, target.bizCode) || hasHandledRequestError(target)) {
		return false
	}
	markRequestErrorHandled(target)
	if (typeof onShow === 'function') {
		onShow(target.bizMsg, target)
		return true
	}
	if (showToast) {
		showToastOnce(target.bizMsg)
	}
	return true
}

export const myRequest = (options = {}) => {
	if (options && options.withToken) {
		options.data = {
			...options.data
		};
	}
	if (options.withLoading) {
		uni.showLoading({
			title: '加载中'
		});
	}

	return new Promise((resolve, reject) => {
		uni.request({
			url: BASE_URL + "/" + options.url,
			method: options.method || "GET",
			data: options.data || {},
			header: {
				Authorization: options.withToken ? uni.getStorageSync("token") : '',
			},
			success: (res) => {
				const response = attachRequestMeta(res, options.successCodes)
				if (includesCode(LOGIN_EXPIRED_CODES, response.bizCode) && !hasHandledRequestError(response)) {
					markRequestErrorHandled(response)
					showToastOnce('登录失效，请重新登录')
					setTimeout(() => {
						uni.reLaunch({
							url: "/pages/tab/index"
						})
					}, 200)
				}
				resolve(response)
			},
			fail: (err) => {
				showToastOnce(options.failMsg || "请求接口失败")
				reject(err)
			},
			complete() {
				uni.hideLoading();
			}
		});
	});
};
//登录判断
export const getId = () => {
	return new Promise((resolve) => {
		uni.getStorage({
			key: 'info',
			success: (res) => {
				console.log(res, "登录成功");
				resolve(200);
			},
			fail: (err) => {
				console.log(err, '登录失败');
				resolve(11003);
			}
		});
	})
}