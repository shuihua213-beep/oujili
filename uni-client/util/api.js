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

const pendingRequests = new Map();

const buildRequestKey = (options) => {
	const data = options.data ? JSON.stringify(options.data) : '';
	return `${options.method || 'GET'}:${options.url}:${data}`;
};

export const myRequest = (options) => {
	if (options && options.withToken) {
		options.data = {
			...options.data
		};
	}

	const dedup = options && options.dedup;
	const dedupKey = options && options.dedupKey;
	const requestKey = dedupKey || buildRequestKey(options);

	if (dedup && pendingRequests.has(requestKey)) {
		const pending = pendingRequests.get(requestKey);
		if (pending && pending.promise) {
			return pending.promise;
		}
	}

	if (options && options.withLoading) {
		uni.showLoading({
			title: '加载中'
		});
	}

	const promise = new Promise((resolve, reject) => {
		const task = uni.request({
			url: BASE_URL + "/" + options.url,
			method: options.method || "GET",
			data: options.data || {},
			header: {
				Authorization: options.withToken ? uni.getStorageSync("token") : '',
			},
			success: (res) => {
				if (res.data.code == 2) {
					uni.showToast({
						icon: 'none',
						title: '登录失效，请重新登录',
						duration: 2000
					})
					setTimeout(() => {
						uni.reLaunch({
							url: "/pages/tab/index"
						})
					}, 200);
				}
				resolve(res);
			},
			fail: (err) => {
				uni.showToast({
					title: "请求接口失败",
				});
				reject(err);
			},
			complete() {
				pendingRequests.delete(requestKey);
				uni.hideLoading();
			}
		});

		pendingRequests.set(requestKey, { task, promise, timestamp: Date.now() });
	});

	return promise;
};

export const cancelPendingRequests = () => {
	pendingRequests.forEach((entry, key) => {
		if (entry && entry.task && typeof entry.task.abort === 'function') {
			entry.task.abort();
		}
	});
	pendingRequests.clear();
};

export const initDummyBatch = async (options) => {
	const {
		dataList = [],
		batchSize = 100,
		url,
		method = 'POST',
		withToken = true,
		extraData = {},
		onProgress
	} = options;

	if (!dataList.length) {
		return { success: 0, duplicated: 0, failed: 0, errors: [] };
	}

	const batches = [];
	for (let i = 0; i < dataList.length; i += batchSize) {
		batches.push(dataList.slice(i, i + batchSize));
	}

	let success = 0;
	let duplicated = 0;
	let failed = 0;
	const errors = [];

	for (let i = 0; i < batches.length; i++) {
		const batch = batches[i];
		try {
			const res = await myRequest({
				url,
				method,
				withToken,
				data: { batchList: batch, ...extraData },
				dedup: true,
				dedupKey: `batch_dummy_init_${i}`
			});

			if (res && res.data && res.data.code === 200) {
				success += batch.length;
			} else {
				failed += batch.length;
				errors.push({
					batchIndex: i,
					count: batch.length,
					msg: res && res.data ? res.data.msg : 'unknown'
				});
			}

			if (onProgress) {
				onProgress({
					current: i + 1,
					total: batches.length,
					batchSize: batch.length,
					success,
					duplicated,
					failed
				});
			}
		} catch (err) {
			duplicated += batch.length;
			errors.push({
				batchIndex: i,
				count: batch.length,
				isDuplicated: true,
				err
			});

			if (onProgress) {
				onProgress({
					current: i + 1,
					total: batches.length,
					batchSize: batch.length,
					success,
					duplicated,
					failed
				});
			}
		}
	}

	return { success, duplicated, failed, errors };
};

//登录判断
export const getId = () => {
	return new Promise((resolve, reject) => {
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